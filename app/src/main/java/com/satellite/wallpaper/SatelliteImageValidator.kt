package com.satellite.wallpaper

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Filtro conservador para impedir que la página de error blanca de WebView
 * se convierta en el fondo. No supone que un JPG decodificable sea una foto válida.
 */
internal object SatelliteImageValidator {
    fun isUsable(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.width < 100 || bitmap.height < 100) return false

        var minLuminance = 255
        var maxLuminance = 0
        var nearWhite = 0
        var colorful = 0
        var samples = 0
        // Muestreo del área completa; evita depender de una resolución de dispositivo.
        for (row in 1..24) for (col in 1..24) {
            val pixel = bitmap.getPixel(bitmap.width * col / 25, bitmap.height * row / 25)
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            val lum = (red * 3 + green * 6 + blue) / 10
            if (lum < minLuminance) minLuminance = lum
            if (lum > maxLuminance) maxLuminance = lum
            if (red >= 242 && green >= 242 && blue >= 242) nearWhite++
            if (maxOf(red, green, blue) - minOf(red, green, blue) > 25) colorful++
            samples++
        }
        // Blanco/negro uniforme, o pantalla casi blanca con letras negras.
        if (maxLuminance - minLuminance < 8) return false
        if (nearWhite * 100 >= samples * 88 && colorful * 100 < samples * 6) return false
        return true
    }
}
