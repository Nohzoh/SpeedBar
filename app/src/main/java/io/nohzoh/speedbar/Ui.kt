package io.nohzoh.speedbar

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowInsetsController

/** Palette : suit le thème clair/sombre du système. */
class Palette(dark: Boolean) {
    private fun c(light: String, darkHex: String, d: Boolean) = Color.parseColor(if (d) darkHex else light)
    val BG = c("#F4F6F9", "#0E1116", dark)
    val SURFACE = c("#FFFFFF", "#181D25", dark)
    val BORDER = c("#E2E6EC", "#262D38", dark)
    val TEXT = c("#111827", "#E8EAED", dark)
    val MUTED = c("#6B7280", "#8A93A0", dark)
    val GREEN = c("#16A34A", "#4ADE80", dark)
    val AMBER = c("#D97706", "#FBBF24", dark)
    val RED = c("#DC2626", "#F87171", dark)
    val BLUE = c("#2563EB", "#60A5FA", dark)
    /** Texte posé sur un bouton plein (vert/orange). */
    val ON_ACCENT = c("#FFFFFF", "#0E1116", dark)

    fun constellation(c: Int): Int = Color.parseColor(
        when (c) {
            GnssStore.GPS -> "#3B82F6"
            GnssStore.GALILEO -> "#A855F7"
            GnssStore.GLONASS -> "#EF4444"
            GnssStore.BEIDOU -> "#F59E0B"
            GnssStore.QZSS -> "#14B8A6"
            GnssStore.NAVIC -> "#EC4899"
            else -> "#94A3B8"
        }
    )
}

fun Context.isDarkTheme() =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** Couleur des barres système et icônes lisibles selon le thème. */
fun Activity.styleSystemBars(pal: Palette) {
    if (Build.VERSION.SDK_INT < 35) {
        @Suppress("DEPRECATION")
        window.statusBarColor = pal.BG
        @Suppress("DEPRECATION")
        window.navigationBarColor = pal.BG
    }
    if (!isDarkTheme()) {
        if (Build.VERSION.SDK_INT >= 30) {
            val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.decorView.post { window.insetsController?.setSystemBarsAppearance(flags, flags) }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }
}
