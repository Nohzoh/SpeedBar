package io.nohzoh.speedbar

import android.location.GnssStatus
import android.location.Location
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Un signal reçu (un satellite peut émettre sur 2 fréquences → 2 signaux). */
data class Sat(
    val constellation: Int,
    val svid: Int,
    val cn0: Float,          // force du signal, dB-Hz
    val elevation: Float,    // degrés au-dessus de l'horizon
    val azimuth: Float,      // degrés depuis le nord
    val used: Boolean,       // utilisé dans le calcul de position
    val freqHz: Float?,
) {
    val key get() = constellation * 1000 + svid
}

/** Un point d'historique (≈ 1 par seconde). */
data class Sample(
    val t: Long,
    val speedKmh: Float?,
    val accuracyM: Float?,
    val satsUsed: Int,
    val satsTracked: Int,
    val avgCn0Used: Float?,
    val hdop: Float?,
)

/**
 * Moyenne mobile exponentielle dans le temps (comme le load average Linux),
 * corrigée du biais de démarrage : au début, c'est la moyenne des valeurs reçues.
 */
class Ema(private val tauMs: Float) {
    private var raw = 0f
    private var weight = 0f
    private var lastT = 0L

    fun add(x: Float, t: Long) {
        val dt = if (lastT == 0L) 1000L else (t - lastT).coerceAtLeast(1L)
        lastT = t
        val a = exp(-dt / tauMs)
        raw = raw * a + x * (1f - a)
        weight = weight * a + (1f - a)
    }

    val value: Float? get() = if (weight > 1e-4f) raw / weight else null
}

/** Statistiques glissantes d'une constellation. */
class ConstStats {
    /** Score 0–100 en moyenne sur 1, 5 et 15 minutes. */
    val score = listOf(Ema(60_000f), Ema(300_000f), Ema(900_000f))
    // Détails sur 5 min, mis à jour seulement quand la constellation est captée.
    val cn0 = Ema(300_000f)
    val usage = Ema(300_000f)
    val used = Ema(300_000f)
    val hdop = Ema(300_000f)
    val visible = Ema(300_000f)
}

/** Données GNSS partagées entre le service (écriture) et les écrans (lecture). Thread principal. */
object GnssStore {
    const val WINDOW_MS = 5 * 60_000L
    private const val STALE_MS = 5_000L

    // Valeurs de GnssStatus (constantes en dur : CONSTELLATION_IRNSS n'existe qu'à partir d'Android 10).
    const val GPS = 1; const val SBAS = 2; const val GLONASS = 3; const val QZSS = 4
    const val BEIDOU = 5; const val GALILEO = 6; const val NAVIC = 7

    private var sats: List<Sat> = emptyList()
    private var statusAt = 0L
    private var location: Location? = null
    private var locationAt = 0L
    var ttffMs: Int? = null
    var startedAt = 0L; private set
    private val stats = mutableMapOf<Int, ConstStats>()
    val constStats: Map<Int, ConstStats> get() = stats

    /** Dilution de précision lue dans les trames NMEA (null si la puce ne la fournit pas). */
    var pdop: Float? = null; private set
    var hdop: Float? = null; private set
    var vdop: Float? = null; private set
    private var dopAt = 0L
    val history = ArrayDeque<Sample>()
    private var lastSampleAt = 0L

    fun reset() {
        sats = emptyList(); statusAt = 0L
        location = null; locationAt = 0L
        ttffMs = null; history.clear(); lastSampleAt = 0L
        pdop = null; hdop = null; vdop = null; dopAt = 0L
        stats.clear(); startedAt = SystemClock.elapsedRealtime()
    }

    fun onStatus(s: GnssStatus) {
        sats = (0 until s.satelliteCount).map { i ->
            Sat(
                constellation = s.getConstellationType(i),
                svid = s.getSvid(i),
                cn0 = s.getCn0DbHz(i),
                elevation = s.getElevationDegrees(i),
                azimuth = s.getAzimuthDegrees(i),
                used = s.usedInFix(i),
                freqHz = if (s.hasCarrierFrequencyHz(i)) s.getCarrierFrequencyHz(i) else null,
            )
        }
        statusAt = SystemClock.elapsedRealtime()
        sample()
    }

    fun onLocation(loc: Location) {
        location = loc
        locationAt = SystemClock.elapsedRealtime()
        sample()
    }

    /**
     * Trames NMEA : GSA donne PDOP/HDOP/VDOP, GGA donne HDOP seul (secours).
     * $GNGSA,A,3,sv1,…,sv12,PDOP,HDOP,VDOP[,systemId]*CS
     */
    fun onNmea(line: String) {
        val body = line.trim().substringBefore('*')
        val f = body.split(',')
        if (f.isEmpty() || f[0].length < 6) return
        fun num(i: Int) = f.getOrNull(i)?.toFloatOrNull()?.takeIf { it > 0f && it < 99f }
        when (f[0].substring(3)) {
            "GSA" -> {
                val p = num(15); val h = num(16); val v = num(17)
                if (h != null) {
                    pdop = p; hdop = h; vdop = v
                    dopAt = SystemClock.elapsedRealtime()
                }
            }
            "GGA" -> {
                val h = num(8)
                // GGA seulement si aucune GSA récente.
                if (h != null && SystemClock.elapsedRealtime() - dopAt > 3_000L) {
                    hdop = h; pdop = null; vdop = null
                    dopAt = SystemClock.elapsedRealtime() - 2_000L
                }
            }
        }
    }

    fun freshHdop(): Float? = hdop?.takeIf { SystemClock.elapsedRealtime() - dopAt < STALE_MS }
    fun freshPdop(): Float? = pdop?.takeIf { SystemClock.elapsedRealtime() - dopAt < STALE_MS }
    fun freshVdop(): Float? = vdop?.takeIf { SystemClock.elapsedRealtime() - dopAt < STALE_MS }

    fun currentSats(): List<Sat> =
        if (SystemClock.elapsedRealtime() - statusAt < STALE_MS) sats else emptyList()

    fun freshLocation(): Location? =
        location?.takeIf { SystemClock.elapsedRealtime() - locationAt < STALE_MS }

    /** Satellites distincts utilisés / captés (un satellite bi-fréquence compte une fois). */
    fun usedCount(list: List<Sat>) = list.filter { it.used }.distinctBy { it.key }.size
    fun trackedCount(list: List<Sat>) = list.filter { it.cn0 > 0f }.distinctBy { it.key }.size
    fun avgCn0Used(list: List<Sat>): Float? =
        list.filter { it.used }.map { it.cn0 }.takeIf { it.isNotEmpty() }?.average()?.toFloat()

    private fun sample() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleAt < 900) return
        lastSampleAt = now
        val loc = freshLocation()
        val list = currentSats()
        history.addLast(
            Sample(
                t = now,
                speedKmh = loc?.takeIf { it.hasSpeed() }?.let { it.speed * 3.6f },
                accuracyM = loc?.takeIf { it.hasAccuracy() }?.accuracy,
                satsUsed = usedCount(list),
                satsTracked = trackedCount(list),
                avgCn0Used = avgCn0Used(list),
                hdop = freshHdop(),
            )
        )
        while (history.isNotEmpty() && now - history.first().t > WINDOW_MS) history.removeFirst()
        if (list.isNotEmpty()) updateConstStats(list, now)
    }

    /**
     * Score par constellation (0–100), heuristique :
     *  35 % force du signal (15 dB-Hz → 0, 40 dB-Hz → max)
     *  25 % part des satellites captés réellement utilisés par la puce
     *  20 % nombre de satellites utilisés (8 et plus → max)
     *  20 % géométrie propre à la constellation (HDOP calculé sur ses seuls satellites)
     * Une constellation non captée compte 0 à cet instant.
     */
    private fun updateConstStats(list: List<Sat>, now: Long) {
        val byConst = list.groupBy { it.constellation }
        for (c in stats.keys + byConst.keys) {
            val st = stats.getOrPut(c) { ConstStats() }
            val sats = byConst[c].orEmpty()
            val tracked = trackedCount(sats)
            st.visible.add(if (tracked > 0) 1f else 0f, now)
            if (tracked == 0) {
                st.score.forEach { it.add(0f, now) }
                continue
            }
            val used = usedCount(sats)
            val cn0 = sats.filter { it.cn0 > 0f }.map { it.cn0 }.average().toFloat()
            val usage = used.toFloat() / tracked
            val h = constellationHdop(sats)
            st.cn0.add(cn0, now)
            st.usage.add(usage, now)
            st.used.add(used.toFloat(), now)
            if (h != null) st.hdop.add(h, now)
            val score = 100f * (
                0.35f * ((cn0 - 15f) / 25f).coerceIn(0f, 1f) +
                0.25f * usage +
                0.20f * (min(used, 8) / 8f) +
                0.20f * (h?.let { ((10f - it) / 9f).coerceIn(0f, 1f) } ?: 0f)
            )
            st.score.forEach { it.add(score, now) }
        }
    }

    /** HDOP qu'on obtiendrait avec les seuls satellites utilisés de cette liste (≥ 4 requis). */
    fun constellationHdop(sats: List<Sat>): Float? {
        val used = sats.filter { it.used && (it.elevation > 0f || it.azimuth > 0f) }.distinctBy { it.key }
        if (used.size < 4) return null
        val n = DoubleArray(16) // GᵀG, G = [est, nord, haut, horloge]
        for (s in used) {
            val el = Math.toRadians(s.elevation.toDouble())
            val az = Math.toRadians(s.azimuth.toDouble())
            val g = doubleArrayOf(cos(el) * sin(az), cos(el) * cos(az), sin(el), 1.0)
            for (i in 0..3) for (j in 0..3) n[i * 4 + j] += g[i] * g[j]
        }
        val q = invert4(n) ?: return null
        val h = sqrt(q[0] + q[5])
        return h.toFloat().takeIf { it.isFinite() && it < 99f }
    }

    private fun invert4(src: DoubleArray): DoubleArray? {
        val a = src.copyOf()
        val inv = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 }
        for (col in 0 until 4) {
            var piv = col
            for (r in col + 1 until 4) if (abs(a[r * 4 + col]) > abs(a[piv * 4 + col])) piv = r
            if (abs(a[piv * 4 + col]) < 1e-9) return null
            if (piv != col) for (k in 0 until 4) {
                var t = a[col * 4 + k]; a[col * 4 + k] = a[piv * 4 + k]; a[piv * 4 + k] = t
                t = inv[col * 4 + k]; inv[col * 4 + k] = inv[piv * 4 + k]; inv[piv * 4 + k] = t
            }
            val p = a[col * 4 + col]
            for (k in 0 until 4) { a[col * 4 + k] /= p; inv[col * 4 + k] /= p }
            for (r in 0 until 4) if (r != col) {
                val f = a[r * 4 + col]
                if (f != 0.0) for (k in 0 until 4) {
                    a[r * 4 + k] -= f * a[col * 4 + k]
                    inv[r * 4 + k] -= f * inv[col * 4 + k]
                }
            }
        }
        return inv
    }

    fun constellationName(c: Int) = when (c) {
        GPS -> "GPS"
        GALILEO -> "Galileo"
        GLONASS -> "GLONASS"
        BEIDOU -> "BeiDou"
        QZSS -> "QZSS"
        SBAS -> "SBAS"
        NAVIC -> "NavIC"
        else -> "Autre"
    }

    fun constellationOrigin(c: Int) = when (c) {
        GPS -> "États-Unis"
        GALILEO -> "Europe"
        GLONASS -> "Russie"
        BEIDOU -> "Chine"
        QZSS -> "Japon"
        SBAS -> "correction"
        NAVIC -> "Inde"
        else -> ""
    }

    /** Nom de la bande de fréquence. */
    fun band(freqHz: Float?): String? {
        val mhz = (freqHz ?: return null) / 1e6f
        fun near(f: Float, tol: Float = 2f) = abs(mhz - f) < tol
        return when {
            near(1575.42f) -> "L1/E1"
            near(1561.098f) -> "B1"
            mhz in 1590f..1610f -> "G1"
            near(1176.45f) -> "L5/E5a"
            near(1207.14f) -> "E5b"
            near(1227.60f) -> "L2"
            mhz in 1240f..1252f -> "G2"
            near(1278.75f) -> "E6"
            else -> "%.0f MHz".format(mhz)
        }
    }

    fun isL5(freqHz: Float?) = freqHz != null && abs(freqHz / 1e6f - 1176.45f) < 2f
}
