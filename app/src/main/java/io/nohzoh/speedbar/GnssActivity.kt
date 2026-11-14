package io.nohzoh.speedbar

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Tableau de bord du signal GNSS : valeurs en direct + historique des 5 dernières minutes. */
class GnssActivity : Activity() {

    private val C by lazy { Palette(isDarkTheme()) }
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }
    private val Int.dp get() = (this * resources.displayMetrics.density).roundToInt()

    private class Tile(val value: TextView, val sub: TextView)

    private lateinit var stateView: TextView
    private lateinit var hintCard: View
    private val tiles = mutableMapOf<String, Tile>()
    private lateinit var sky: SkyPlotView
    private lateinit var bars: SignalBarsView
    private lateinit var consts: ConstellationView
    private lateinit var quality: QualityView
    private lateinit var qualityNote: TextView
    private val sparks = mutableListOf<Pair<SparkView, (Sample) -> Float?>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        styleSystemBars(C)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(C.BG)
            addView(content)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, 0) // zone de défilement sous la barre d'état
                content.setPadding(16.dp, 12.dp, 16.dp, bars.bottom + 24.dp)
                insets
            }
        }

        // En-tête
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = text("‹", 30f, C.TEXT).apply {
            setPadding(4.dp, 0, 16.dp, 4.dp)
            setOnClickListener { finish() }
        }
        header.addView(back)
        header.addView(text("Signal GPS", 20f, C.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        stateView = text("", 13f, C.MUTED)
        header.addView(stateView)
        content.addView(header)

        hintCard = card(C.AMBER).apply {
            addView(text("Le suivi est arrêté. Lance « Démarrer » sur l'écran principal : " +
                "l'historique se remplit ensuite même écran éteint.", 14f, C.TEXT))
        }
        content.addView(hintCard, block(12))

        // Nombre de colonnes selon la largeur (recalculé à chaque rotation, l'activité étant recréée).
        val widthDp = resources.configuration.screenWidthDp
        val tileCols = (widthDp / 200).coerceIn(2, 4)
        val panelCols = (widthDp / 380).coerceIn(1, 2)

        // Tuiles
        val tileDefs = listOf(
            "sats" to "Satellites utilisés",
            "acc" to "Précision horizontale",
            "cn0" to "Signal moyen",
            "dual" to "Bi-fréquence",
            "alt" to "Altitude",
            "spdacc" to "Précision vitesse",
            "bearing" to "Cap",
            "ttff" to "Premier fix",
            "dop" to "Géométrie des satellites (HDOP)",
        )
        val tileViews = tileDefs.map { (key, title) ->
            card().apply {
                setPadding(14.dp, 12.dp, 14.dp, 12.dp)
                addView(text(title, 12f, C.MUTED))
                val value = text("—", 22f, C.TEXT, bold = true)
                val sub = text(" ", 12f, C.MUTED)
                addView(value); addView(sub)
                tiles[key] = Tile(value, sub)
            }
        }
        addGrid(content, tileViews, tileCols, top = 10)

        // Historique
        content.addView(section("5 dernières minutes"))
        val sparkCards = listOf(
            spark("Vitesse (km/h)", C.GREEN, { "%.0f".format(it) }) { it.speedKmh },
            spark("Précision horizontale (m)", C.AMBER, { "%.0f".format(it) }) { it.accuracyM },
            spark("HDOP · géométrie (plus bas = mieux)", C.RED, { "%.1f".format(it) }) { it.hdop },
            spark("Satellites utilisés", C.BLUE, { "%.0f".format(it) }) { it.satsUsed.toFloat() },
            spark("Signal moyen des utilisés (dB-Hz)", C.constellation(GnssStore.GALILEO),
                { "%.0f".format(it) }, floorZero = false) { it.avgCn0Used },
        )
        addGrid(content, sparkCards, panelCols, top = 8)

        // Constellations
        consts = ConstellationView(this, C)
        val constPanel = panel("Constellations · utilisés / captés", card().apply { addView(consts) })

        // Qualité dans le temps
        quality = QualityView(this, C)
        qualityNote = text("", 12f, C.MUTED).apply { setPadding(0, 10.dp, 0, 0) }
        val qualityPanel = panel("Qualité des constellations · score 0–100",
            card().apply { addView(quality); addView(qualityNote) })

        // Ciel
        sky = SkyPlotView(this, C)
        val skyPanel = panel("Position des satellites dans le ciel", card().apply {
            addView(sky)
            addView(text("Centre = au-dessus de toi, bord = horizon. Plein = utilisé pour la position, " +
                "cercle = capté seulement. Taille = force du signal.", 12f, C.MUTED).apply {
                setPadding(0, 8.dp, 0, 0)
            })
        })

        // Signaux
        bars = SignalBarsView(this, C)
        val barsPanel = panel("Force du signal par satellite (dB-Hz)", card().apply {
            addView(bars)
            addView(text("Au-dessus de 30 : bon · 20 à 30 : moyen · sous 20 : faible. " +
                "Barre pâle = capté mais pas utilisé.", 12f, C.MUTED).apply { setPadding(0, 8.dp, 0, 0) })
        })

        addGrid(content, listOf(constPanel, qualityPanel, skyPanel, barsPanel), panelCols, top = 0)

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun refresh() {
        val running = SpeedService.running
        val sats = GnssStore.currentSats()
        val loc = GnssStore.freshLocation()
        val now = SystemClock.elapsedRealtime()

        stateView.text = when {
            !running -> "○  Arrêté"
            loc == null -> "●  Recherche…"
            else -> "●  Fix GPS"
        }
        stateView.setTextColor(when {
            !running -> C.MUTED
            loc == null -> C.AMBER
            else -> C.GREEN
        })
        hintCard.visibility = if (running) View.GONE else View.VISIBLE

        // Satellites
        val used = GnssStore.usedCount(sats)
        val tracked = GnssStore.trackedCount(sats)
        val inView = sats.distinctBy { it.key }.size
        set("sats", if (sats.isEmpty()) "—" else "$used / $tracked",
            if (sats.isEmpty()) "" else "sur $tracked captés · $inView en vue")

        // Précision
        val acc = loc?.takeIf { it.hasAccuracy() }?.accuracy
        set("acc", acc?.let { "± ${it.roundToInt()} m" } ?: "—",
            when {
                acc == null -> ""
                acc < 5 -> "excellente"
                acc < 15 -> "bonne"
                acc < 50 -> "moyenne"
                else -> "faible"
            },
            when {
                acc == null -> C.TEXT
                acc < 15 -> C.GREEN
                acc < 50 -> C.AMBER
                else -> C.RED
            })

        // Signal moyen
        val avg = GnssStore.avgCn0Used(sats)
        set("cn0", avg?.let { "%.0f dB-Hz".format(it) } ?: "—",
            when {
                avg == null -> ""
                avg >= 35 -> "fort"
                avg >= 25 -> "correct"
                else -> "faible"
            },
            when {
                avg == null -> C.TEXT
                avg >= 30 -> C.GREEN
                avg >= 20 -> C.AMBER
                else -> C.RED
            })

        // Bi-fréquence (L1 + L5 : position plus précise en ville)
        val usedBands = sats.filter { it.used }.mapNotNull { GnssStore.band(it.freqHz) }.distinct()
        val hasFreq = sats.any { it.freqHz != null }
        val dual = sats.any { it.used && GnssStore.isL5(it.freqHz) }
        set("dual",
            when {
                sats.isEmpty() -> "—"
                !hasFreq -> "Inconnu"
                dual -> "Oui"
                else -> "Non"
            },
            if (usedBands.isNotEmpty()) usedBands.joinToString(" + ")
            else if (sats.isNotEmpty() && !hasFreq) "non fourni par le téléphone" else "",
            if (dual) C.GREEN else C.TEXT)

        // Altitude
        val alt = loc?.takeIf { it.hasAltitude() }?.altitude
        val vAcc = loc?.takeIf { it.hasVerticalAccuracy() }?.verticalAccuracyMeters
        set("alt", alt?.let { "${it.roundToInt()} m" } ?: "—",
            vAcc?.let { "± ${it.roundToInt()} m" } ?: "")

        // Précision vitesse
        val sAcc = loc?.takeIf { it.hasSpeedAccuracy() }?.speedAccuracyMetersPerSecond
        set("spdacc", sAcc?.let { "± %.1f km/h".format(it * 3.6f) } ?: "—", "")

        // Cap (seulement significatif en mouvement)
        val moving = loc != null && loc.hasSpeed() && loc.speed > 1f
        val bearing = loc?.takeIf { moving && it.hasBearing() }?.bearing
        set("bearing", bearing?.let { "${it.roundToInt()}°" } ?: "—",
            bearing?.let { cardinal(it) } ?: if (loc != null && !moving) "à l'arrêt" else "")

        // Temps jusqu'au premier fix
        val ttff = GnssStore.ttffMs
        set("ttff", ttff?.let { "%.1f s".format(it / 1000f) } ?: "—",
            if (ttff != null) "depuis le démarrage" else "")

        // DOP : multiplicateur d'erreur dû à la répartition des satellites dans le ciel
        val hdop = GnssStore.freshHdop()
        val pdop = GnssStore.freshPdop(); val vdop = GnssStore.freshVdop()
        val dopQuality = when {
            hdop == null -> ""
            hdop < 1f -> "idéale"
            hdop < 2f -> "excellente"
            hdop < 5f -> "bonne"
            hdop < 10f -> "moyenne"
            else -> "faible"
        }
        val dopDetails = listOfNotNull(
            dopQuality.takeIf { it.isNotEmpty() },
            pdop?.let { "PDOP %.1f".format(it) },
            vdop?.let { "VDOP %.1f".format(it) },
        ).joinToString(" · ")
        set("dop", hdop?.let { "%.1f".format(it) } ?: "—",
            when {
                hdop != null -> "$dopDetails\nPrécision ≈ erreur par satellite × HDOP"
                running && sats.isNotEmpty() -> "non fourni par le téléphone"
                else -> ""
            },
            when {
                hdop == null -> C.TEXT
                hdop < 2f -> C.GREEN
                hdop < 5f -> C.AMBER
                else -> C.RED
            })

        // Graphes
        val history = GnssStore.history.toList()
        for ((view, selector) in sparks) {
            view.points = history.map { it.t to selector(it) }
            view.now = now
            view.invalidate()
        }
        consts.update(sats)
        quality.update(GnssStore.constStats
            .filterKeys { it != GnssStore.SBAS && it != 0 }
            .map { (c, st) ->
                val details = listOfNotNull(
                    st.cn0.value?.let { "%.0f dB-Hz".format(it) },
                    st.usage.value?.let { "${(it * 100).roundToInt()} % util." },
                    st.used.value?.let { "%.1f sat.".format(it) },
                    st.hdop.value?.let { "HDOP %.1f".format(it) },
                    st.visible.value?.let { "vue ${(it * 100).roundToInt()} %" },
                ).joinToString(" · ")
                QualityView.Row(c, st.score.map { it.value }, details)
            }
            .sortedByDescending { it.scores[1] ?: 0f })
        val minutes = if (running && GnssStore.startedAt > 0) (now - GnssStore.startedAt) / 60_000 else null
        qualityNote.text = buildString {
            append("Moyennes glissantes, comme le load average. Score = force du signal (35 %), " +
                "part des satellites captés que la puce utilise (« util. », 25 %), nombre utilisé (20 %), " +
                "géométrie propre à la constellation (20 %). « vue » = part du temps où elle est captée. " +
                "Échelle absolue : ~80+ ciel dégagé, ~40 en train ou en intérieur. Détails : moyenne sur 5 min.")
            if (minutes != null) {
                append("\nSuivi depuis $minutes min")
                if (minutes < 15) append(" : la colonne 15 min n'a pas encore tout son recul.")
                else append(".")
            }
        }
        sky.sats = sats; sky.invalidate()
        bars.sats = sats; bars.invalidate()
    }

    private fun cardinal(deg: Float): String {
        val names = listOf("nord", "nord-est", "est", "sud-est", "sud", "sud-ouest", "ouest", "nord-ouest")
        return names[(((deg % 360) + 360) % 360 / 45f).roundToInt() % 8]
    }

    private fun set(key: String, value: String, sub: String, color: Int = C.TEXT) {
        val t = tiles[key] ?: return
        t.value.text = value
        t.value.setTextColor(color)
        t.sub.text = sub.ifEmpty { " " }
    }

    private fun spark(
        title: String, color: Int, format: (Float) -> String,
        floorZero: Boolean = true, selector: (Sample) -> Float?,
    ): View {
        val v = SparkView(this, C).apply {
            this.title = title
            lineColor = color
            this.format = format
            this.floorZero = floorZero
        }
        sparks += v to selector
        return card().apply { setPadding(0, 0, 0, 0); addView(v) }
    }

    /** Range les vues en lignes de [cols] colonnes de même largeur. */
    private fun addGrid(parent: LinearLayout, items: List<View>, cols: Int, top: Int) {
        items.chunked(cols).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { i, v ->
                line.addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    if (i > 0) marginStart = 10.dp
                })
            }
            parent.addView(line, block(top))
        }
    }

    /** Titre de section + carte, rangés ensemble dans la grille. */
    private fun panel(title: String, card: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(section(title))
        addView(card, block(8))
    }

    // ---- Helpers ----

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun section(title: String) = text(title.uppercase(), 12f, C.MUTED, bold = true).apply {
        letterSpacing = 0.06f
        setPadding(4.dp, 22.dp, 0, 0)
    }

    private fun card(stroke: Int = C.BORDER) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(C.SURFACE)
            cornerRadius = 16.dp.toFloat()
            setStroke(1.dp, stroke)
        }
        setPadding(16.dp, 16.dp, 16.dp, 16.dp)
    }

    private fun block(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = top.dp }
}
