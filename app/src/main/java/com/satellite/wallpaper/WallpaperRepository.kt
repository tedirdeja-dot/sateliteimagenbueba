package com.satellite.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.AtomicFile
import android.view.ViewGroup
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object WallpaperRepository {

    private const val TAG = "WallpaperRepository"
    private const val CACHE_NAME = "satellite_cache.jpg"
    private const val PREFS = "satellite_wallpaper"
    private const val KEY_ZOOM = "zoom"
    private const val KEY_OFFSET_X = "offset_x"
    private const val KEY_OFFSET_Y = "offset_y"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_INTERVAL_MINUTES = "unlock_interval_minutes"
    private const val KEY_VIEW_URL = "view_url"
    private const val KEY_LOCK_STATIC = "lock_static"
    private const val KEY_LOG = "attempt_log"

    /** Si una captura no termina en este tiempo se libera el bloqueo para no quedar atascados. */
    private const val WATCHDOG_MS = 300_000L

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    /** Un único lector/escritor de la caché durante la sustitución atómica. */
    private val cacheLock = Any()
    @Volatile private var runToken = 0L
    // Se modifica en el hilo principal: conserva desbloqueos que llegan durante otra captura.
    private var pendingUnlocks = 0
    // Los cambios de zona tienen prioridad sobre un desbloqueo en curso.
    private var pendingSelection: ((Boolean) -> Unit)? = null

    // Observadores del estado de actualización mientras la pantalla principal está visible.
    // Todas las altas, bajas y notificaciones se gestionan en el hilo principal.
    private val refreshStateListeners = mutableSetOf<(Boolean) -> Unit>()

    fun isUpdating(): Boolean = running.get() || pendingSelection != null || pendingUnlocks > 0

    fun addRefreshStateListener(listener: (Boolean) -> Unit) {
        main.post {
            refreshStateListeners.add(listener)
            listener(isUpdating())
        }
    }

    fun removeRefreshStateListener(listener: (Boolean) -> Unit) {
        main.post { refreshStateListeners.remove(listener) }
    }

    private fun notifyRefreshStateListeners() {
        val updating = isUpdating()
        refreshStateListeners.toList().forEach { listener ->
            runCatching { listener(updating) }
        }
    }

    /** Motivo del último fallo de captura (null si la última fue correcta). */
    @Volatile
    var lastError: String? = null
        private set

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

    fun cacheFile(context: Context): File = File(context.filesDir, CACHE_NAME)

    // ---------- Selección de vista (recordada) ----------

    /** true si el usuario ya eligió una vista de Zoom Earth. */
    fun hasSelection(context: Context): Boolean = prefs(context).contains(KEY_VIEW_URL)

    /** URL de Zoom Earth elegida por el usuario (o la de por defecto). */
    fun viewUrl(context: Context): String =
        prefs(context).getString(KEY_VIEW_URL, null) ?: ZoomEarthCapture.PAGE_URL

    /** Guarda la vista elegida. Si cambia, invalida la imagen en caché y el encuadre. */
    fun saveViewUrl(context: Context, url: String) {
        val p = prefs(context)
        val changed = p.getString(KEY_VIEW_URL, null) != url
        val e = p.edit().putString(KEY_VIEW_URL, url)
        if (changed) {
            e.remove(KEY_UPDATED_AT).remove(KEY_ZOOM).remove(KEY_OFFSET_X).remove(KEY_OFFSET_Y)
            // Conservamos la imagen previa hasta terminar correctamente la nueva captura.
            // Una descarga antigua no podrá reemplazar la nueva zona (comprobación en done).
        }
        e.apply()
    }

    fun unlockIntervalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL_MINUTES, 30).coerceAtLeast(0)

    fun setUnlockIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL_MINUTES, minutes.coerceAtLeast(0)).apply()
    }

    fun refreshOnUnlock(context: Context) {
        val interval = unlockIntervalMinutes(context)
        // "Sin esperas": cada desbloqueo real debe provocar una captura aunque
        // la imagen anterior se haya descargado hace segundos.
        refresh(context, force = interval == 0,
            minAgeMs = interval.toLong() * 60_000L,
            source = "desbloqueo")
    }

    fun lockEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LOCK_STATIC, true)

    fun setLockEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCK_STATIC, value).apply()
    }

    // ---------- Caché / encuadre ----------

    fun updatedAt(context: Context): Long {
        val v = prefs(context).getLong(KEY_UPDATED_AT, 0L)
        return if (v > 0) v else cacheFile(context).lastModified()
    }

    fun loadCached(context: Context): Bitmap? = synchronized(cacheLock) {
        // AtomicFile.openRead() recupera también una copia anterior si hubo un
        // cierre inesperado mientras se sustituía el JPG.
        val bmp = runCatching {
            AtomicFile(cacheFile(context)).openRead().use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return@synchronized null
        if (SatelliteImageValidator.isUsable(bmp)) bmp else {
            bmp.recycle()
            // No borrar la caché: la última imagen válida nunca se elimina por un error de lectura.
            null
        }
    }

    fun loadState(context: Context): CropState {
        val p = prefs(context)
        return CropState(
            p.getFloat(KEY_ZOOM, 1f),
            p.getFloat(KEY_OFFSET_X, 0f),
            p.getFloat(KEY_OFFSET_Y, 0f)
        ).clamped()
    }

    fun saveState(context: Context, state: CropState) {
        val s = state.clamped()
        prefs(context).edit()
            .putFloat(KEY_ZOOM, s.zoom)
            .putFloat(KEY_OFFSET_X, s.offsetX)
            .putFloat(KEY_OFFSET_Y, s.offsetY)
            .apply()
    }

    // ---------- Registro de intentos (diagnóstico) ----------

    @Synchronized
    fun note(context: Context, source: String, text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val old = prefs(context).getString(KEY_LOG, "")!!.split("\n").filter { it.isNotBlank() }
        val log = (old + "$time · $source · $text").takeLast(10).joinToString("\n")
        prefs(context).edit().putString(KEY_LOG, log).apply()
        Log.i(TAG, "$source: $text")
    }

    /** Últimos intentos de descarga (más reciente al final). */
    fun attemptLog(context: Context): String = prefs(context).getString(KEY_LOG, "")!!

    // ---------- Descarga ----------

    /**
     * Captura la vista elegida de Zoom Earth y actualiza la caché (y la pantalla de bloqueo).
     * Misma estructura que la APK original: refresh() hace el trabajo, al terminar avisa con el
     * broadcast CACHE_UPDATED y el fondo animado vuelve a pintarse.
     * force = false: no hace nada si la caché tiene menos de [minAgeMs], cuando se indica un intervalo.
     * host != null: captura con el WebView visible dentro de ese contenedor (modo fiable).
     * host == null: captura en segundo plano (cascada de estrategias).
     * callback(true) solo si se generó una imagen nueva; se invoca en el hilo principal.
     */
    fun refresh(
        context: Context,
        force: Boolean = false,
        host: ViewGroup? = null,
        minAgeMs: Long = 0L,
        source: String = "manual",
        callback: (Boolean) -> Unit = {}
    ) {
        val app = context.applicationContext
        main.post {
            val ageMs = System.currentTimeMillis() - updatedAt(app)
            val fresh = cacheFile(app).exists() && ageMs <= minAgeMs
            if (!force && fresh) {
                note(app, source, "omitido: la imagen es de hace ${ageMs / 1000} s (mínimo ${minAgeMs / 1000} s)")
                callback(false); return@post
            }
            if (!running.compareAndSet(false, true)) {
                if (source == "seleccion") {
                    // No se descarta: se ejecuta en cuanto termine la captura actual.
                    // Solo importa la última zona elegida.
                    pendingSelection?.invoke(false)
                    pendingSelection = callback
                    notifyRefreshStateListeners()
                    note(app, source, "en cola prioritaria: esperando captura en curso")
                    return@post
                }
                if (source == "desbloqueo") {
                    pendingUnlocks = 1
                    note(app, source, "en cola: hay otra captura en curso")
                } else {
                    note(app, source, "omitido: ya hay una descarga en curso")
                }
                callback(false); return@post
            }
            val token = System.nanoTime()
            runToken = token
            notifyRefreshStateListeners()
            note(app, source, "iniciando captura (${if (host != null) "WebView visible" else "segundo plano"})")

            // Red de seguridad: si la captura no termina nunca, se libera el bloqueo.
            main.postDelayed({
                if (runToken == token) {
                    runToken = 0L
                    running.set(false)
                    note(app, source, "FALLO: la captura no terminó en ${WATCHDOG_MS / 1000} s; se libera el bloqueo")
                    notifyRefreshStateListeners()
                    callback(false)
                    startNextPendingSelectionOrUnlock(app)
                }
            }, WATCHDOG_MS)

            val selectedUrl = viewUrl(app)
            val tmp = File(app.filesDir, "$CACHE_NAME.$token.download")
            val done: (String?) -> Unit = { error ->
                executor.execute {
                    // Nunca guardamos una imagen de la zona anterior después de cambiarla.
                    val validSelection = selectedUrl == viewUrl(app)
                    val ok = error == null && validSelection && runToken == token && commit(app, tmp)
                    if (!ok) tmp.delete()
                    main.post {
                        if (runToken != token) return@post // el vigilante ya liberó esta captura
                        runToken = 0L
                        running.set(false)
                        lastError = if (ok) null else (error
                            ?: if (!validSelection) "la zona cambió durante la descarga" else "la imagen capturada no es válida")
                        note(app, source, if (ok) "OK, imagen actualizada [${ZoomEarthCapture.lastMode}]" else "FALLO: $lastError")
                        notifyRefreshStateListeners()
                        callback(ok)
                        if (ok) {
                            // Primero se repinta el fondo animado (como la APK original); la pantalla de bloqueo estática después.
                            app.sendBroadcast(
                                Intent(SatelliteWallpaperService.ACTION_CACHE_UPDATED)
                                    .setPackage(app.packageName)
                            )
                            if (lockEnabled(app)) executor.execute { applyLockScreen(app) }
                        }
                        startNextPendingSelectionOrUnlock(app)
                    }
                }
            }
            try {
                if (host != null) ZoomEarthCapture.capture(app, tmp, host, selectedUrl) { done(it) }
                else ZoomEarthCapture.captureBackground(app, tmp, selectedUrl) { done(it) }
            } catch (t: Throwable) {
                done("excepción al iniciar la captura: ${t.javaClass.simpleName} ${t.message}")
            }
        }
    }

    /** Prioriza la última zona seleccionada y después atiende los desbloqueos pendientes. */
    private fun startNextPendingSelectionOrUnlock(app: Context) {
        if (running.get()) return
        val callback = pendingSelection
        if (callback != null) {
            pendingSelection = null
            refresh(app, force = true, source = "seleccion", callback = callback)
            return
        }
        if (pendingUnlocks > 0) {
            pendingUnlocks--
            refreshOnUnlock(app)
        }
    }

    /**
     * Pinta la imagen encuadrada (con fecha y hora) como fondo ESTÁTICO de la pantalla de bloqueo.
     * Garantiza que el bloqueo se actualice aunque el launcher no muestre el fondo animado ahí.
     */
    fun applyLockScreen(context: Context) {
        if (Build.VERSION.SDK_INT < 24) return
        val app = context.applicationContext
        val src = loadCached(app) ?: return
        val (sw, sh) = ScreenSize.real(app)
        val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(out)
            c.drawColor(0xFF10212B.toInt())
            CropMath.draw(c, src, out.width, out.height, loadState(app))
            CropMath.drawUpdatedAt(c, updatedAt(app), out.width, out.height)
            WallpaperManager.getInstance(app)
                .setBitmap(out, null, true, WallpaperManager.FLAG_LOCK)
        } catch (t: Throwable) {
            Log.w(TAG, "applyLockScreen", t)
        } finally {
            src.recycle()
            out.recycle()
        }
    }

    fun applyLockScreenAsync(context: Context) {
        val app = context.applicationContext
        executor.execute { applyLockScreen(app) }
    }

    private fun commit(app: Context, tmp: File): Boolean = try {
        // Verificar también el JPEG temporal antes de reemplazar el último bueno:
        // una captura de Chrome con texto negro sobre fondo blanco es decodificable.
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        val candidate = BitmapFactory.decodeFile(tmp.absolutePath, opts)
            ?: throw IllegalStateException("JPEG temporal ilegible")
        val usable = try { SatelliteImageValidator.isUsable(candidate) } finally { candidate.recycle() }
        check(usable) { "La captura es una pantalla en blanco o un error web" }
        synchronized(cacheLock) {
            val atomic = AtomicFile(cacheFile(app))
            var pendingStream: FileOutputStream? = null
            try {
                val stream = atomic.startWrite()
                pendingStream = stream
                tmp.inputStream().use { input -> input.copyTo(stream) }
                atomic.finishWrite(stream)
                pendingStream = null
            } catch (t: Throwable) {
                pendingStream?.let { runCatching { atomic.failWrite(it) } }
                throw t
            }
        }
        // Actualizar la hora únicamente después de confirmar el reemplazo.
        prefs(app).edit().putLong(KEY_UPDATED_AT, System.currentTimeMillis()).apply()
        true
    } catch (t: Throwable) {
        Log.w(TAG, "Se conserva la imagen anterior; nueva captura rechazada", t)
        false
    } finally {
        runCatching { tmp.delete() }
    }
}
