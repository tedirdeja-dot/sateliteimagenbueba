package com.satellite.wallpaper

import android.app.Activity
import android.content.ComponentName
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var preview: CropPreviewView
    private lateinit var button: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var previewContainer: android.view.ViewGroup
    private lateinit var changeView: Button
    private lateinit var grantOverlay: Button
    private lateinit var lockStatic: CheckBox
    private var leavingActivity = false
    private var latestDownloadRequestId = 0L
    private var uiDownloadActive = false
    private val refreshStateListener: (Boolean) -> Unit = { updating ->
        if (isActivityUsable()) {
            when {
                updating || uiDownloadActive -> {
                    setWallpaperButtonState(WallpaperButtonState.UPDATING)
                    if (updating && !uiDownloadActive) {
                        progress.visibility = View.VISIBLE
                        status.visibility = View.VISIBLE
                        status.setText(R.string.loading)
                    }
                }
                else -> loadCachedImage()
            }
        }
    }

    /** Mantiene texto, color y accesibilidad del botón sincronizados con la descarga. */
    private enum class WallpaperButtonState { UPDATING, READY, UNAVAILABLE }

    private fun setWallpaperButtonState(state: WallpaperButtonState) {
        val downloading = state == WallpaperButtonState.UPDATING
        button.setText(if (downloading) R.string.updating else R.string.set_wallpaper)
        val colorRes = if (state == WallpaperButtonState.READY) {
            R.color.accent
        } else {
            R.color.button_updating
        }
        button.backgroundTintList = ColorStateList.valueOf(getColor(colorRes))
        button.isEnabled = state == WallpaperButtonState.READY
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySafeBottomInset()

        preview = findViewById(R.id.crop_preview)
        button = findViewById(R.id.set_wallpaper)
        setWallpaperButtonState(WallpaperButtonState.UNAVAILABLE)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        previewContainer = findViewById(R.id.preview_container)
        status.setOnClickListener { startDownload() }

        preview.onStateChanged = { WallpaperRepository.saveState(this, it) }
        button.setOnClickListener { openConfirm() }

        changeView = findViewById(R.id.change_view)
        grantOverlay = findViewById(R.id.grant_overlay)
        lockStatic = findViewById(R.id.lock_static)
        installUnlockIntervalSelector()

        changeView.setOnClickListener { openPicker() }
        grantOverlay.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.grant_battery).setOnClickListener {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
        findViewById<Button>(R.id.refresh_now).setOnClickListener { startDownload() }
        UnlockService.start(this) // mantiene la app viva y escucha el desbloqueo aunque esté cerrada
        lockStatic.isChecked = WallpaperRepository.lockEnabled(this)
        lockStatic.setOnCheckedChangeListener { _, checked ->
            WallpaperRepository.setLockEnabled(this, checked)
            if (checked) WallpaperRepository.applyLockScreenAsync(this)
        }

        if (!WallpaperRepository.hasSelection(this)) {
            // Primer inicio: el usuario elige qué vista de Zoom Earth quiere.
            openPicker()
        } else {
            loadCachedImage()
        }
    }

    private fun installUnlockIntervalSelector() {
        // 0 significa actualizar en cada desbloqueo, sin intervalo mínimo.
        val options = listOf(0, 5, 10, 15, 30, 60, 120, 360, 720, 1440)
        val labels = options.map {
            when {
                it == 0 -> "Sin esperas"
                it < 60 -> "$it minutos"
                it == 60 -> "1 hora"
                else -> "${it / 60} horas"
            }
        }
        val parent = lockStatic.parent as? android.view.ViewGroup ?: return
        val index = parent.indexOfChild(lockStatic)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }
        container.addView(TextView(this).apply { text = "Intervalo mínimo entre actualizaciones al desbloquear" })
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.setSelection(options.indexOf(WallpaperRepository.unlockIntervalMinutes(this)).takeIf { it >= 0 } ?: options.indexOf(30))
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                WallpaperRepository.setUnlockIntervalMinutes(this@MainActivity, options[position])
            }
        }
        container.addView(spinner)
        parent.addView(container, index.coerceAtLeast(0))
    }

    private fun versionLabel(): String = runCatching {
        val pi = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        "v${pi.versionName} (build $code)"
    }.getOrDefault("v?")

    override fun onStart() {
        super.onStart()
        WallpaperRepository.addRefreshStateListener(refreshStateListener)
    }

    override fun onStop() {
        WallpaperRepository.removeRefreshStateListener(refreshStateListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.version).text = "Versión ${versionLabel()}"
        grantOverlay.visibility =
            if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        val ignoring = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        findViewById<View>(R.id.grant_battery).visibility = if (ignoring) View.GONE else View.VISIBLE
        val log = WallpaperRepository.attemptLog(this)
        findViewById<TextView>(R.id.attempt_log).text =
            if (log.isBlank()) "${versionLabel()} · sin intentos en segundo plano todavía." else "${versionLabel()} · últimos intentos:\n$log"
    }

    private fun openPicker() {
        startActivityForResult(Intent(this, PickViewActivity::class.java), REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (!isActivityUsable()) return
        if (requestCode == REQ_CONFIRM) {
            if (resultCode == RESULT_OK) saveAndOpenWallpaperPicker()
            return
        }
        if (requestCode != REQ_PICK) return
        val hasImage = WallpaperRepository.cacheFile(this).exists()
        if (resultCode == RESULT_OK) {
            // Nueva zona: descarga inmediata, sin aplicar el intervalo de desbloqueo.
            startDownload("seleccion")
        } else if (hasImage) {
            loadCachedImage()
        } else {
            showManualDownloadPrompt()
        }
    }

    private fun loadCachedImage() {
        val cached = runCatching { WallpaperRepository.loadCached(this) }.getOrNull()
        if (cached != null) {
            showImageSafely(cached)
        } else {
            showManualDownloadPrompt()
        }
    }

    private fun showManualDownloadPrompt() {
        progress.visibility = View.GONE
        status.visibility = View.VISIBLE
        status.setText(R.string.manual_download_prompt)
        setWallpaperButtonState(WallpaperButtonState.UNAVAILABLE)
    }

    override fun onDestroy() {
        leavingActivity = true
        super.onDestroy()
    }

    private fun applySafeBottomInset() {
        val root = findViewById<View>(R.id.root_container)
        val basePadding = root.paddingBottom
        val extra = (24 * resources.displayMetrics.density).toInt()
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars()).bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, basePadding + bottom + extra)
            insets
        }
        root.requestApplyInsets()
    }

    private fun startDownload(source: String = "manual") {
        // Si ya se actualiza al desbloquear, no iniciamos una segunda descarga manual simultánea.
        if (source != "seleccion" && WallpaperRepository.isUpdating()) {
            setWallpaperButtonState(WallpaperButtonState.UPDATING)
            return
        }
        // La última solicitud visual tiene prioridad; callbacks anteriores no restauran el naranja.
        val requestId = ++latestDownloadRequestId
        uiDownloadActive = true
        progress.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        status.setText(R.string.loading)
        setWallpaperButtonState(WallpaperButtonState.UPDATING)
        captureNow(fallbackToWindow = true, source = source, requestId = requestId) { ok ->
            if (!isActivityUsable() || requestId != latestDownloadRequestId) return@captureNow
            uiDownloadActive = false
            progress.visibility = View.GONE
            // Aunque la descarga haya fallado, mostrar el JPG anterior y permitir usarlo.
            val bmp = runCatching { WallpaperRepository.loadCached(this) }.getOrNull()
            if (bmp != null) {
                showImageSafely(bmp)
                if (!ok) {
                    status.visibility = View.VISIBLE
                    status.setText(R.string.kept_previous_image)
                    // La imagen anterior sigue disponible para usar como fondo.
                    setWallpaperButtonState(WallpaperButtonState.READY)
                }
            } else {
                showError()
            }
        }
    }

    /**
     * Captura con el mismo método que el fondo en segundo plano (imagen del tamaño y proporción de la
     * pantalla). Si falla y [fallbackToWindow], reintenta con el WebView visible dentro de la app.
     */
    private fun captureNow(
        fallbackToWindow: Boolean,
        source: String,
        requestId: Long,
        onDone: (Boolean) -> Unit
    ) {
        WallpaperRepository.refresh(this, force = true, host = null, source = source) { ok ->
            if (!isActivityUsable() || requestId != latestDownloadRequestId) return@refresh
            if (ok || !fallbackToWindow) {
                onDone(ok)
            } else {
                // El WebView visible puede recuperar una captura fallida; el botón continúa gris.
                progress.visibility = View.GONE
                status.visibility = View.GONE
                WallpaperRepository.refresh(
                    this, force = true, host = previewContainer, source = "$source (ventana)"
                ) { ok2 ->
                    if (requestId == latestDownloadRequestId) onDone(ok2)
                }
            }
        }
    }

    /** Muestra la imagen a pantalla completa con Aceptar / Cancelar antes de aplicar el fondo. */
    private fun openConfirm() {
        WallpaperRepository.saveState(this, preview.state)
        startActivityForResult(Intent(this, ConfirmWallpaperActivity::class.java), REQ_CONFIRM)
    }

    private fun showImageSafely(bitmap: Bitmap) {
        try {
            showImage(bitmap)
        } catch (t: Throwable) {
            showError()
        }
    }

    private fun showImage(bitmap: Bitmap) {
        progress.visibility = View.GONE
        status.visibility = View.GONE
        preview.setBitmap(
            bitmap,
            WallpaperRepository.loadState(this),
            WallpaperRepository.updatedAt(this)
        )
        setWallpaperButtonState(
            if (uiDownloadActive || WallpaperRepository.isUpdating()) WallpaperButtonState.UPDATING
            else WallpaperButtonState.READY
        )
    }

    private fun showError() {
        if (!isActivityUsable()) return
        progress.visibility = View.GONE
        status.visibility = View.VISIBLE
        val reason = WallpaperRepository.lastError
        status.text = getString(R.string.download_error) +
            (if (reason != null) "\n\nMotivo: $reason" else "") +
            "\n\n" + getString(R.string.tap_retry)
        setWallpaperButtonState(
            if (uiDownloadActive || WallpaperRepository.isUpdating()) WallpaperButtonState.UPDATING
            else WallpaperButtonState.UNAVAILABLE
        )
    }

    private fun isActivityUsable(): Boolean = !(leavingActivity || isFinishing || isDestroyed)

    private fun saveAndOpenWallpaperPicker() {
        WallpaperRepository.saveState(this, preview.state)
        if (WallpaperRepository.lockEnabled(this)) WallpaperRepository.applyLockScreenAsync(this)
        val component = ComponentName(this, SatelliteWallpaperService::class.java)
        val intent = Intent("android.service.wallpaper.CHANGE_LIVE_WALLPAPER").apply {
            putExtra("android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT", component)
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            startActivity(Intent("android.service.wallpaper.LIVE_WALLPAPER_CHOOSER"))
        }
    }

    companion object {
        private const val REQ_PICK = 1001
        private const val REQ_CONFIRM = 1002
    }
}
