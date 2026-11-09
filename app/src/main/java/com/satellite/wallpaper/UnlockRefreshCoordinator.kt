package com.satellite.wallpaper

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.Handler
import android.os.Looper

/**
 * Unifica los avisos de desbloqueo del servicio de fondo y del servicio persistente.
 * Una sola descarga por desbloqueo, y siempre con el intervalo elegido por el usuario.
 * El fondo de pantalla solo hace la comprobación alternativa si se detectó pantalla apagada.
 */
internal object UnlockRefreshCoordinator {
    private const val PREFS = "satellite_unlock_events"
    private const val SCREEN_OFF_AT = "screen_off_at"
    private const val LAST_DISPATCH_AT = "last_dispatch_at"
    private const val DUPLICATE_MS = 2_500L
    private const val MAX_SCREEN_OFF_AGE_MS = 24L * 60 * 60 * 1000

    @Synchronized
    fun screenOff(context: Context) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, 0).edit()
            .putLong(SCREEN_OFF_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Alternativa breve para teléfonos que omiten USER_PRESENT. Nunca descarga
     * mientras el dispositivo sigue bloqueado; no se programa ninguna tarea periódica.
     */
    fun screenOn(context: Context) {
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({ wallpaperVisible(app) }, 1_600L)
        Handler(Looper.getMainLooper()).postDelayed({ wallpaperVisible(app) }, 4_000L)
    }

    /** Evento real emitido por Android después de quitar el bloqueo. */
    @Synchronized
    fun userPresent(context: Context, origin: String) {
        dispatch(context.applicationContext, origin, requireScreenOff = false)
    }

    /** Alternativa cuando el fondo vuelve a ser visible tras apagar la pantalla. */
    @Synchronized
    fun wallpaperVisible(context: Context) {
        dispatch(context.applicationContext, "fondo visible tras desbloqueo", requireScreenOff = true)
    }

    private fun dispatch(app: Context, origin: String, requireScreenOff: Boolean) {
        if (!WallpaperRepository.hasSelection(app)) return
        val now = System.currentTimeMillis()
        val prefs = app.getSharedPreferences(PREFS, 0)
        val offAt = prefs.getLong(SCREEN_OFF_AT, 0L)
        val armed = offAt > 0L && now >= offAt && now - offAt < MAX_SCREEN_OFF_AGE_MS
        if (requireScreenOff && !armed) return

        val interactive = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val locked = (app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        if (!interactive || locked) return

        val last = prefs.getLong(LAST_DISPATCH_AT, 0L)
        if (last > 0 && now >= last && now - last < DUPLICATE_MS) return

        prefs.edit().putLong(LAST_DISPATCH_AT, now).remove(SCREEN_OFF_AT).apply()
        WallpaperRepository.note(app, "desbloqueo", "detectado por $origin; comprobando intervalo")
        WallpaperRepository.refreshOnUnlock(app)
    }
}
