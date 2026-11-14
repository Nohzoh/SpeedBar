package io.nohzoh.speedbar

import android.location.GnssStatus
import android.location.Location
import android.os.SystemClock
import kotlin.math.abs

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
)

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
    val history = ArrayDeque<Sample>()
    private var lastSampleAt = 0L

    fun reset() {
        sats = emptyList(); statusAt = 0L
        location = null; locationAt = 0L
        ttffMs = null; history.clear(); lastSampleAt = 0L
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
            )
        )
        while (history.isNotEmpty() && now - history.first().t > WINDOW_MS) history.removeFirst()
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
