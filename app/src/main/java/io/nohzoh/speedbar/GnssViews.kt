package io.nohzoh.speedbar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private fun View.dp(v: Float) = v * resources.displayMetrics.density
private fun View.sp(v: Float) =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

private fun paint(color: Int, size: Float = 0f, bold: Boolean = false, stroke: Float = 0f) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        if (size > 0f) textSize = size
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        if (stroke > 0f) { style = Paint.Style.STROKE; strokeWidth = stroke }
    }

private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

/** Mini-graphe façon Grafana : courbe sur les 5 dernières minutes + valeur actuelle. */
class SparkView(ctx: Context, private val pal: Palette) : View(ctx) {
    var title = ""
    var lineColor = pal.BLUE
    var format: (Float) -> String = { "%.0f".format(it) }
    var floorZero = true
    var points: List<Pair<Long, Float?>> = emptyList()
    var now = 0L

    private val titleP = paint(pal.MUTED, sp(12f))
    private val valueP = paint(pal.TEXT, sp(18f), bold = true).apply { textAlign = Paint.Align.RIGHT }
    private val axisP = paint(pal.MUTED, sp(10f))
    private val gridP = paint(pal.BORDER, stroke = dp(1f))
    private val lineP = paint(pal.BLUE, stroke = dp(2f)).apply {
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fillP = paint(pal.BLUE)

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), dp(142f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val pad = dp(14f)
        c.drawText(title, pad, pad + titleP.textSize, titleP)
        val last = points.lastOrNull()?.second
        c.drawText(last?.let(format) ?: "—", w - pad, pad + valueP.textSize, valueP)

        val top = pad + valueP.textSize + dp(10f) + axisP.textSize
        val bottom = h - pad - axisP.textSize - dp(4f)
        val left = pad; val right = w - pad
        for (i in 0..2) {
            val y = top + (bottom - top) * i / 2f
            c.drawLine(left, y, right, y, gridP)
        }
        axisP.textAlign = Paint.Align.LEFT
        c.drawText("−5 min", left, h - pad, axisP)
        axisP.textAlign = Paint.Align.RIGHT
        c.drawText("maintenant", right, h - pad, axisP)

        val vals = points.mapNotNull { it.second }
        if (vals.isEmpty()) {
            axisP.textAlign = Paint.Align.CENTER
            c.drawText("pas encore de données", w / 2, (top + bottom) / 2, axisP)
            return
        }
        val lo = if (floorZero) 0f else vals.min()
        var hi = vals.max()
        if (hi - lo < 1f) hi = lo + 1f
        hi += (hi - lo) * 0.15f

        axisP.textAlign = Paint.Align.LEFT
        c.drawText("max " + format(hi), left, top - dp(4f), axisP)

        val window = GnssStore.WINDOW_MS.toFloat()
        fun x(t: Long) = left + (right - left) * (1f - (now - t) / window).coerceIn(0f, 1f)
        fun y(v: Float) = bottom - (bottom - top) * ((v - lo) / (hi - lo)).coerceIn(0f, 1f)

        val line = Path(); val fill = Path()
        var open = false; var lastX = 0f
        for ((t, v) in points) {
            if (v == null) {
                if (open) { fill.lineTo(lastX, bottom); fill.close(); open = false }
                continue
            }
            val px = x(t); val py = y(v)
            if (!open) {
                line.moveTo(px, py); fill.moveTo(px, bottom); fill.lineTo(px, py); open = true
            } else {
                line.lineTo(px, py); fill.lineTo(px, py)
            }
            lastX = px
        }
        if (open) { fill.lineTo(lastX, bottom); fill.close() }
        fillP.color = withAlpha(lineColor, 0x30)
        lineP.color = lineColor
        c.drawPath(fill, fillP)
        c.drawPath(line, lineP)
    }
}

/** Vue du ciel : centre = zénith, bord = horizon, nord en haut. */
class SkyPlotView(ctx: Context, private val pal: Palette) : View(ctx) {
    var sats: List<Sat> = emptyList()

    private val ringP = paint(pal.BORDER, stroke = dp(1f))
    private val labelP = paint(pal.MUTED, sp(11f)).apply { textAlign = Paint.Align.CENTER }
    private val dotP = paint(pal.BLUE)
    private val ringDotP = paint(pal.BLUE, stroke = dp(1.5f))
    private val svidP = paint(pal.TEXT, sp(8f)).apply { textAlign = Paint.Align.CENTER }

    override fun onMeasure(w: Int, h: Int) {
        val size = MeasureSpec.getSize(w)
        setMeasuredDimension(size, min(size, dp(320f).toInt()))
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(cx, cy) - dp(18f)
        for (el in listOf(0, 30, 60)) c.drawCircle(cx, cy, r * (90 - el) / 90f, ringP)
        c.drawLine(cx - r, cy, cx + r, cy, ringP)
        c.drawLine(cx, cy - r, cx, cy + r, ringP)
        val off = labelP.textSize / 3
        c.drawText("N", cx, cy - r - dp(5f), labelP)
        c.drawText("S", cx, cy + r + dp(5f) + labelP.textSize - off, labelP)
        c.drawText("E", cx + r + dp(9f), cy + off, labelP)
        c.drawText("O", cx - r - dp(9f), cy + off, labelP)

        // Un point par satellite (le signal le plus fort s'il émet sur 2 fréquences).
        val unique = sats.groupBy { it.key }.values.map { g ->
            g.maxBy { it.cn0 }.copy(used = g.any { it.used })
        }.filter { it.elevation > 0f || it.azimuth > 0f }
        // Non utilisés d'abord, utilisés par-dessus.
        for (s in unique.sortedBy { it.used }) {
            val rr = r * (90f - s.elevation.coerceIn(0f, 90f)) / 90f
            val a = Math.toRadians(s.azimuth.toDouble())
            val x = cx + rr * sin(a).toFloat()
            val y = cy - rr * cos(a).toFloat()
            val size = dp(4f) + dp(5f) * (s.cn0.coerceIn(0f, 45f) / 45f)
            val col = pal.constellation(s.constellation)
            if (s.used) {
                dotP.color = col
                c.drawCircle(x, y, size, dotP)
            } else {
                ringDotP.color = withAlpha(col, if (s.cn0 > 0f) 0xFF else 0x70)
                c.drawCircle(x, y, size, ringDotP)
            }
            if (s.used && size > dp(7f)) {
                svidP.color = pal.ON_ACCENT
                c.drawText(s.svid.toString(), x, y + svidP.textSize / 3, svidP)
            }
        }
    }
}

/** Force du signal de chaque satellite (barre pleine = utilisé). */
class SignalBarsView(ctx: Context, private val pal: Palette) : View(ctx) {
    var sats: List<Sat> = emptyList()

    private val gridP = paint(pal.BORDER, stroke = dp(1f))
    private val axisP = paint(pal.MUTED, sp(10f))
    private val barP = paint(pal.BLUE)

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), dp(150f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val left = dp(30f); val right = width - dp(4f)
        val top = dp(8f); val bottom = height - dp(8f)
        val maxDb = 50f
        for (db in listOf(10, 20, 30, 40)) {
            val y = bottom - (bottom - top) * db / maxDb
            c.drawLine(left, y, right, y, gridP)
            c.drawText(db.toString(), dp(4f), y + axisP.textSize / 3, axisP)
        }
        c.drawLine(left, bottom, right, bottom, gridP)
        val list = sats.filter { it.cn0 > 0f }
            .sortedWith(compareBy<Sat>({ it.constellation }, { -it.cn0 }))
        if (list.isEmpty()) {
            axisP.textAlign = Paint.Align.CENTER
            c.drawText("aucun signal capté", (left + right) / 2, (top + bottom) / 2, axisP)
            axisP.textAlign = Paint.Align.LEFT
            return
        }
        val slot = (right - left) / max(list.size, 12)
        val gap = min(dp(2f), slot * 0.25f)
        list.forEachIndexed { i, s ->
            val x0 = left + i * slot + gap / 2
            val y = bottom - (bottom - top) * (s.cn0.coerceAtMost(maxDb) / maxDb)
            barP.color = withAlpha(pal.constellation(s.constellation), if (s.used) 0xFF else 0x55)
            c.drawRect(x0, y, x0 + slot - gap, bottom, barP)
        }
    }
}

/** Une ligne par constellation : utilisés / captés, barre de proportion, signal moyen. */
class ConstellationView(ctx: Context, private val pal: Palette) : View(ctx) {
    private data class Row(val c: Int, val used: Int, val tracked: Int, val avg: Float?, val bands: String)
    private var rows: List<Row> = emptyList()

    private val nameP = paint(pal.TEXT, sp(14f), bold = true)
    private val subP = TextPaint(paint(pal.MUTED, sp(11f)))
    private val valueP = paint(pal.TEXT, sp(13f)).apply { textAlign = Paint.Align.RIGHT }
    private val trackP = paint(pal.BORDER)
    private val fillP = paint(pal.BLUE)
    private val rowH get() = dp(46f)

    fun update(sats: List<Sat>) {
        val newRows = sats.groupBy { it.constellation }.map { (c, list) ->
            Row(
                c = c,
                used = GnssStore.usedCount(list),
                tracked = GnssStore.trackedCount(list),
                avg = GnssStore.avgCn0Used(list),
                bands = list.filter { it.cn0 > 0f }.mapNotNull { GnssStore.band(it.freqHz) }
                    .distinct().joinToString(" + "),
            )
        }.sortedByDescending { it.used * 100 + it.tracked }
        val relayout = newRows.size != rows.size
        rows = newRows
        if (relayout) requestLayout()
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (rowH * max(rows.size, 1)).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (rows.isEmpty()) {
            subP.textAlign = Paint.Align.CENTER
            c.drawText("aucune constellation captée", width / 2f, rowH / 2, subP)
            subP.textAlign = Paint.Align.LEFT
            return
        }
        val w = width.toFloat()
        rows.forEachIndexed { i, r ->
            val y0 = i * rowH
            val col = pal.constellation(r.c)
            fillP.color = col
            c.drawCircle(dp(6f), y0 + dp(14f), dp(5f), fillP)
            c.drawText(GnssStore.constellationName(r.c), dp(18f), y0 + dp(19f), nameP)
            val nameW = nameP.measureText(GnssStore.constellationName(r.c))
            val sub = listOf(GnssStore.constellationOrigin(r.c), r.bands).filter { it.isNotEmpty() }.joinToString(" · ")
            val avg = r.avg?.let { " · %.0f dB-Hz".format(it) } ?: ""
            val value = "${r.used} / ${r.tracked}$avg"
            c.drawText(value, w, y0 + dp(19f), valueP)
            val subX = dp(24f) + nameW
            val avail = w - valueP.measureText(value) - dp(10f) - subX
            if (avail > 0f) {
                val fitted = TextUtils.ellipsize(sub, subP, avail, TextUtils.TruncateAt.END).toString()
                c.drawText(fitted, subX, y0 + dp(19f), subP)
            }
            val by = y0 + dp(29f)
            c.drawRoundRect(dp(18f), by, w, by + dp(6f), dp(3f), dp(3f), trackP)
            val ratio = if (r.tracked > 0) r.used.toFloat() / r.tracked else 0f
            if (ratio > 0f) c.drawRoundRect(dp(18f), by, dp(18f) + (w - dp(18f)) * ratio, by + dp(6f), dp(3f), dp(3f), fillP)
        }
    }
}

/** Score de chaque constellation sur 1 / 5 / 15 min, avec le détail sur 5 min. */
class QualityView(ctx: Context, private val pal: Palette) : View(ctx) {
    data class Row(val c: Int, val scores: List<Float?>, val details: String)
    private var rows: List<Row> = emptyList()

    private val headP = paint(pal.MUTED, sp(11f)).apply { textAlign = Paint.Align.CENTER }
    private val nameP = paint(pal.TEXT, sp(14f), bold = true)
    private val scoreP = paint(pal.TEXT, sp(16f), bold = true).apply { textAlign = Paint.Align.CENTER }
    private val detailP = TextPaint(paint(pal.MUTED, sp(11f)))
    private val dotP = paint(pal.BLUE)
    private val headH get() = dp(22f)
    private val rowH get() = dp(50f)
    private val colW get() = dp(54f)

    fun update(newRows: List<Row>) {
        val relayout = newRows.size != rows.size
        rows = newRows
        if (relayout) requestLayout()
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (headH + rowH * max(rows.size, 1)).toInt())
    }

    private fun scoreColor(v: Float) = when {
        v >= 70f -> pal.GREEN
        v >= 40f -> pal.AMBER
        else -> pal.RED
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val cols = listOf("1 min", "5 min", "15 min")
        cols.forEachIndexed { i, label ->
            c.drawText(label, w - colW * (2 - i) - colW / 2, dp(13f), headP)
        }
        if (rows.isEmpty()) {
            detailP.textAlign = Paint.Align.CENTER
            c.drawText("en attente de données", w / 2, headH + rowH / 2, detailP)
            detailP.textAlign = Paint.Align.LEFT
            return
        }
        rows.forEachIndexed { i, r ->
            val y0 = headH + i * rowH
            dotP.color = pal.constellation(r.c)
            c.drawCircle(dp(6f), y0 + dp(15f), dp(5f), dotP)
            c.drawText(GnssStore.constellationName(r.c), dp(18f), y0 + dp(20f), nameP)
            r.scores.forEachIndexed { k, v ->
                scoreP.color = v?.let { scoreColor(it) } ?: pal.MUTED
                c.drawText(v?.let { "%.0f".format(it) } ?: "—", w - colW * (2 - k) - colW / 2, y0 + dp(21f), scoreP)
            }
            val fitted = TextUtils.ellipsize(r.details, detailP, w - dp(18f), TextUtils.TruncateAt.END).toString()
            c.drawText(fitted, dp(18f), y0 + dp(39f), detailP)
        }
    }
}
