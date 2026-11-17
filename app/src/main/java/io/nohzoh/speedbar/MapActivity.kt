package io.nohzoh.speedbar

import android.app.Activity
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import kotlin.math.roundToInt

/** Carte du trajet depuis le démarrage du suivi (fond OpenStreetMap). */
class MapActivity : Activity() {

    private val C by lazy { Palette(isDarkTheme()) }
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }
    private val Int.dp get() = (this * resources.displayMetrics.density).roundToInt()

    private lateinit var map: MapView
    private lateinit var line: Polyline
    private lateinit var startMarker: Marker
    private lateinit var posMarker: Marker
    private lateinit var stateView: TextView
    private val stats = mutableMapOf<String, TextView>()
    private var shownPoints = 0
    private var follow = true
    private var firstFixCentered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        styleSystemBars(C)

        val root = FrameLayout(this).apply { setBackgroundColor(C.BG) }

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            controller.setZoom(16.0)
            // En thème sombre, on inverse les couleurs des tuiles.
            if (isDarkTheme()) overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            // Déplacer la carte à la main désactive le suivi automatique.
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_MOVE) follow = false
                false
            }
        }
        line = Polyline(map).apply {
            outlinePaint.color = C.BLUE
            outlinePaint.strokeWidth = 5.dp.toFloat()
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            setOnClickListener { _, _, _ -> true } // pas de bulle au toucher
        }
        startMarker = dotMarker(C.GREEN, 14)
        posMarker = dotMarker(C.BLUE, 20)
        map.overlays.add(line)
        map.overlays.add(startMarker)
        map.overlays.add(posMarker)
        map.overlays.add(CopyrightOverlay(this))
        root.addView(map, FrameLayout.LayoutParams(-1, -1))

        // En-tête flottant : retour, titre, état, statistiques
        val header = card().apply { setPadding(16.dp, 10.dp, 16.dp, 12.dp) }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(text("‹", 28f, C.TEXT).apply {
            setPadding(0, 0, 14.dp, 4.dp)
            setOnClickListener { finish() }
        })
        titleRow.addView(text("Trajet", 18f, C.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        stateView = text("", 13f, C.MUTED)
        titleRow.addView(stateView)
        header.addView(titleRow)

        val statsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("dist" to "Distance", "time" to "Durée", "avg" to "Moyenne", "max" to "Max").forEach { (k, label) ->
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(label, 11f, C.MUTED))
            val v = text("—", 16f, C.TEXT, bold = true)
            col.addView(v)
            stats[k] = v
            statsRow.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        header.addView(statsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6.dp })
        val headerLp = FrameLayout.LayoutParams(-1, -2, Gravity.TOP)
        root.addView(header, headerLp)

        // Boutons flottants : recentrer et voir tout le trajet
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        buttons.addView(roundButton("⤢") { follow = false; fitTrack(animated = true) })
        buttons.addView(roundButton("◎") {
            follow = true
            currentPoint()?.let { map.controller.animateTo(it) }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 10.dp })
        val buttonsLp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
        root.addView(buttons, buttonsLp)

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            headerLp.setMargins(bars.left + 12.dp, bars.top + 8.dp, bars.right + 12.dp, 0)
            header.layoutParams = headerLp
            buttonsLp.setMargins(0, 0, bars.right + 16.dp, bars.bottom + 36.dp)
            buttons.layoutParams = buttonsLp
            insets
        }

        setContentView(root)
        map.addOnFirstLayoutListener { _, _, _, _, _ ->
            refresh()
            if (GnssStore.track.size > 1) { follow = false; fitTrack(animated = false) }
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        map.onPause()
        super.onPause()
    }

    private fun currentPoint(): GeoPoint? {
        val loc = GnssStore.freshLocation()
        if (loc != null) return GeoPoint(loc.latitude, loc.longitude)
        return GnssStore.track.lastOrNull()?.let { GeoPoint(it.lat, it.lon) }
    }

    private fun fitTrack(animated: Boolean) {
        val pts = GnssStore.track.map { GeoPoint(it.lat, it.lon) }
        when {
            pts.size > 1 -> map.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(pts), animated, 80.dp)
            pts.size == 1 -> map.controller.setCenter(pts[0])
        }
    }

    private fun refresh() {
        val track = GnssStore.track
        val running = SpeedService.running

        // Tracé : ajout incrémental des nouveaux points (repart de zéro après un redémarrage).
        if (track.size < shownPoints) {
            line.setPoints(emptyList())
            shownPoints = 0
        }
        for (i in shownPoints until track.size) line.addPoint(GeoPoint(track[i].lat, track[i].lon))
        shownPoints = track.size

        val first = track.firstOrNull()
        startMarker.isEnabled = first != null
        if (first != null) startMarker.position = GeoPoint(first.lat, first.lon)

        val pos = currentPoint()
        posMarker.isEnabled = pos != null
        if (pos != null) {
            posMarker.position = pos
            if (!firstFixCentered && track.size <= 1) {
                map.controller.setCenter(pos); firstFixCentered = true
            } else if (follow) map.controller.animateTo(pos)
        }

        // État et statistiques
        val fresh = GnssStore.freshLocation() != null
        stateView.text = when {
            !running && track.isNotEmpty() -> "○  Dernier trajet"
            !running -> "○  Arrêté"
            !fresh -> "●  Recherche GPS…"
            else -> "●  En cours"
        }
        stateView.setTextColor(when {
            !running -> C.MUTED
            !fresh -> C.AMBER
            else -> C.GREEN
        })

        val distKm = GnssStore.distanceM / 1000.0
        val durationMs = when {
            track.size > 1 -> (if (running) SystemClock.elapsedRealtime() else track.last().t) - track.first().t
            else -> 0L
        }
        stats["dist"]?.text = if (track.isEmpty()) "—"
            else if (distKm < 1) "${GnssStore.distanceM.roundToInt()} m" else "%.1f km".format(distKm)
        stats["time"]?.text = if (durationMs <= 0) "—" else formatDuration(durationMs)
        stats["avg"]?.text = if (durationMs < 10_000) "—"
            else "%.0f km/h".format(distKm / (durationMs / 3_600_000.0))
        stats["max"]?.text = if (GnssStore.maxSpeedKmh <= 0f) "—" else "%.0f km/h".format(GnssStore.maxSpeedKmh)

        map.invalidate()
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60
        return if (h > 0) "${h} h ${"%02d".format(m)}" else "$m min ${"%02d".format(s % 60)}"
    }

    // ---- Helpers ----

    private fun dotMarker(color: Int, sizeDp: Int) = Marker(map).apply {
        icon = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(3.dp, android.graphics.Color.WHITE)
            setSize(sizeDp.dp, sizeDp.dp)
        }
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setInfoWindow(null)
        setOnMarkerClickListener { _, _ -> true }
        isEnabled = false
    }

    private fun roundButton(label: String, onClick: () -> Unit) = text(label, 22f, C.TEXT).apply {
        gravity = Gravity.CENTER
        width = 52.dp; height = 52.dp
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(C.SURFACE)
            setStroke(1.dp, C.BORDER)
        }
        elevation = 4.dp.toFloat()
        setOnClickListener { onClick() }
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(C.SURFACE)
            cornerRadius = 16.dp.toFloat()
            setStroke(1.dp, C.BORDER)
        }
        elevation = 4.dp.toFloat()
    }
}
