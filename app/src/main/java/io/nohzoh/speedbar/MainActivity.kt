package io.nohzoh.speedbar

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Écran principal : compteur en direct, démarrage, état, vitesses notées. */
class MainActivity : Activity() {

    /** Palette : suit le thème clair/sombre du système. */
    private class Palette(dark: Boolean) {
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
    }

    private val isDark by lazy {
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }
    private val C by lazy { Palette(isDark) }

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private lateinit var stateView: TextView
    private lateinit var speedView: TextView
    private lateinit var unitView: TextView
    private lateinit var gaugeFill: View
    private lateinit var gaugeRest: View
    private lateinit var gaugeLabel: TextView
    private lateinit var gaugeRow: LinearLayout
    private lateinit var toggleButton: Button
    private lateinit var markButton: Button
    private lateinit var problemsCard: LinearLayout
    private lateinit var problemsView: TextView
    private lateinit var marksView: TextView
    private lateinit var clearButton: Button

    private val Int.dp get() = (this * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            window.statusBarColor = C.BG
            @Suppress("DEPRECATION")
            window.navigationBarColor = C.BG
        }
        // En thème clair, icônes sombres dans les barres système pour qu'elles restent visibles.
        if (!isDark) {
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

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(C.BG)
            isFillViewport = true
            addView(content)
            // Android 15 impose l'edge-to-edge : on décale le contenu sous les barres système.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                content.setPadding(20.dp, bars.top + 16.dp, 20.dp, bars.bottom + 24.dp)
                insets
            }
        }

        // En-tête : nom + état
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text("SpeedBar", 20f, C.TEXT, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f))
        stateView = text("", 14f, C.MUTED)
        header.addView(stateView)
        content.addView(header)

        // Carte compteur
        val speedCard = card().apply { gravity = Gravity.CENTER_HORIZONTAL }
        speedView = text("--", 112f, C.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum" // chiffres à chasse fixe : pas de saut d'affichage
            includeFontPadding = false
        }
        unitView = text("km/h", 18f, C.MUTED).apply { gravity = Gravity.CENTER }
        speedCard.addView(speedView, LinearLayout.LayoutParams(-1, -2))
        speedCard.addView(unitView, LinearLayout.LayoutParams(-1, -2))

        // Jauge vers le seuil
        gaugeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(C.BORDER, 4.dp)
            clipToOutline = true
        }
        gaugeFill = View(this).apply { background = rounded(C.GREEN, 4.dp) }
        gaugeRest = View(this)
        gaugeRow.addView(gaugeFill, LinearLayout.LayoutParams(0, -1, 0f))
        gaugeRow.addView(gaugeRest, LinearLayout.LayoutParams(0, -1, 1f))
        speedCard.addView(gaugeRow, LinearLayout.LayoutParams(-1, 8.dp).apply { topMargin = 24.dp })
        gaugeLabel = text("", 13f, C.MUTED).apply { gravity = Gravity.CENTER }
        speedCard.addView(gaugeLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp })
        content.addView(speedCard, block(top = 16))

        // Actions
        toggleButton = pill("Démarrer", C.GREEN, C.ON_ACCENT).apply {
            setOnClickListener {
                if (SpeedService.running) {
                    startService(Intent(this@MainActivity, SpeedService::class.java)
                        .setAction(SpeedService.ACTION_STOP))
                    handler.postDelayed({ refresh() }, 300)
                } else startWithPermissions()
            }
        }
        content.addView(toggleButton, block(top = 16, height = 56))

        markButton = pill("Noter la vitesse actuelle", C.SURFACE, C.TEXT, stroke = C.BORDER).apply {
            setOnClickListener {
                startService(Intent(this@MainActivity, SpeedService::class.java)
                    .setAction(SpeedService.ACTION_MARK))
                handler.postDelayed({ refresh() }, 300)
            }
        }
        content.addView(markButton, block(top = 10, height = 52))

        // Problèmes éventuels
        problemsCard = card(stroke = C.AMBER)
        problemsView = text("", 14f, C.TEXT).apply { setLineSpacing(4.dp.toFloat(), 1f) }
        problemsCard.addView(problemsView)
        val settings = pill("Ouvrir les réglages de l'app", C.AMBER, C.ON_ACCENT).apply {
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)))
            }
        }
        problemsCard.addView(settings, LinearLayout.LayoutParams(-1, 48.dp).apply { topMargin = 12.dp })
        content.addView(problemsCard, block(top = 16))

        // Vitesses notées
        val marksCard = card()
        val marksHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        marksHeader.addView(text("Vitesses notées", 15f, C.TEXT, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f))
        clearButton = Button(this).apply {
            text = "Effacer"
            isAllCaps = false
            setTextColor(C.BLUE)
            background = null
            minHeight = 0; minimumHeight = 0
            setOnClickListener {
                getSharedPreferences(SpeedService.PREFS, Context.MODE_PRIVATE)
                    .edit().remove(SpeedService.KEY_MARKS).apply()
                refresh()
            }
        }
        marksHeader.addView(clearButton)
        marksCard.addView(marksHeader)
        marksView = text("", 14f, C.MUTED).apply { setLineSpacing(4.dp.toFloat(), 1f) }
        marksCard.addView(marksView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6.dp })
        content.addView(marksCard, block(top = 16))

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        SpeedService.onUpdate = { refresh() }
        handler.post(ticker)
    }

    override fun onPause() {
        SpeedService.onUpdate = null
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        val running = SpeedService.running
        val speed = SpeedService.currentSpeed()
        val marks = SpeedService.marks(this)
        val threshold = marks.minOrNull()

        // État
        stateView.text = when {
            !running -> "○  Arrêté"
            speed == null -> "●  Recherche GPS…"
            else -> "●  En cours"
        }
        stateView.setTextColor(when {
            !running -> C.MUTED
            speed == null -> C.AMBER
            else -> C.GREEN
        })

        // Compteur
        val color = speedColor(speed, threshold)
        speedView.text = speed?.roundToInt()?.toString() ?: "--"
        speedView.setTextColor(if (speed == null) C.MUTED else color)

        // Jauge
        if (threshold != null && threshold > 0f) {
            gaugeRow.visibility = View.VISIBLE
            val ratio = ((speed ?: 0f) / threshold).coerceIn(0f, 1f)
            (gaugeFill.layoutParams as LinearLayout.LayoutParams).weight = ratio
            (gaugeRest.layoutParams as LinearLayout.LayoutParams).weight = 1f - ratio
            gaugeFill.background = rounded(color, 4.dp)
            gaugeRow.requestLayout()
            gaugeLabel.text = "Seuil : ${threshold.roundToInt()} km/h"
        } else {
            gaugeRow.visibility = View.GONE
            gaugeLabel.text = if (running) "Note une vitesse pour définir un seuil" else ""
        }

        // Boutons
        toggleButton.text = if (running) "Arrêter" else "Démarrer"
        toggleButton.background = if (running) rounded(C.SURFACE, 28.dp, C.RED) else rounded(C.GREEN, 28.dp)
        toggleButton.setTextColor(if (running) C.RED else C.ON_ACCENT)
        markButton.isEnabled = speed != null
        markButton.alpha = if (speed != null) 1f else 0.4f

        // Problèmes
        val problems = problems()
        problemsCard.visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
        problemsView.text = problems.joinToString("\n")

        // Vitesses notées
        marksView.text = if (marks.isEmpty())
            "Aucune. Note la vitesse au moment où une app te bloque : la plus basse devient le seuil."
        else marks.joinToString("   ·   ") { "${it.roundToInt()} km/h" }
        clearButton.visibility = if (marks.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Vert sous 80 % du seuil, orange jusqu'au seuil, rouge au-delà. */
    private fun speedColor(speed: Float?, threshold: Float?): Int = when {
        speed == null || threshold == null -> C.TEXT
        speed >= threshold -> C.RED
        speed >= threshold * 0.8f -> C.AMBER
        else -> C.GREEN
    }

    private fun problems(): List<String> {
        val list = mutableListOf<String>()
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            list += if (granted(Manifest.permission.ACCESS_COARSE_LOCATION))
                "⚠  Localisation « approximative » : la vitesse GPS nécessite « précise »."
            else "⚠  Localisation non autorisée."
        }
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            list += "⚠  Notifications désactivées : rien ne s'affichera dans la barre d'état."
        }
        if (!getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            list += "⚠  GPS désactivé sur le téléphone."
        }
        SpeedService.lastError?.let { list += "⚠  $it" }
        return list
    }

    private fun startWithPermissions() {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { !granted(it) }

        if (needed.isEmpty()) {
            startForegroundService(Intent(this, SpeedService::class.java))
            handler.postDelayed({ refresh() }, 500)
        } else {
            requestPermissions(needed.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            startForegroundService(Intent(this, SpeedService::class.java))
            handler.postDelayed({ refresh() }, 500)
        } else refresh()
    }

    // ---- Petits helpers d'interface (aucune dépendance) ----

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (stroke != null) setStroke(1.dp, stroke)
    }

    private fun card(stroke: Int = C.BORDER) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C.SURFACE, 20.dp, stroke)
        setPadding(20.dp, 20.dp, 20.dp, 20.dp)
    }

    private fun pill(label: String, fill: Int, textColor: Int, stroke: Int? = null) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(textColor)
        background = rounded(fill, 28.dp, stroke)
        stateListAnimator = null
    }

    private fun block(top: Int, height: Int? = null) =
        LinearLayout.LayoutParams(-1, height?.dp ?: -2).apply { topMargin = top.dp }
}
