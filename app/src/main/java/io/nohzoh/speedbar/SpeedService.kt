package io.nohzoh.speedbar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.SystemClock
import kotlin.math.roundToInt

/**
 * Service au premier plan : écoute le GPS et affiche la vitesse (km/h)
 * dans l'icône de la barre d'état, via une notification persistante.
 */
class SpeedService : Service(), LocationListener {

    companion object {
        const val ACTION_STOP = "io.nohzoh.speedbar.STOP"
        const val ACTION_MARK = "io.nohzoh.speedbar.MARK"
        // Canal en importance DEFAULT (mais muet) : certains téléphones masquent
        // l'icône des notifications « silencieuses » (LOW) dans la barre d'état.
        private const val CHANNEL_ID = "speed_v2"
        private const val OLD_CHANNEL_ID = "speed"

        /** État lu par l'écran principal. */
        @Volatile var running = false
        @Volatile var lastError: String? = null

        /** Dernière vitesse connue, partagée avec l'écran principal. */
        @Volatile private var lastSpeedKmh: Float? = null
        @Volatile private var lastFixAt = 0L

        /** Appelé (thread principal) à chaque nouvelle mesure. */
        var onUpdate: (() -> Unit)? = null

        /** Vitesse actuelle en km/h, ou null si pas de fix GPS récent. */
        fun currentSpeed(): Float? {
            val fresh = SystemClock.elapsedRealtime() - lastFixAt < STALE_MS
            return if (running && fresh) lastSpeedKmh else null
        }

        fun marks(ctx: Context): List<Float> {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MARKS, "") ?: ""
            return raw.split(";").mapNotNull { it.replace(',', '.').toFloatOrNull() }
        }
        private const val NOTIF_ID = 1
        /** Au-delà de ce délai sans fix GPS, on affiche "--". */
        private const val STALE_MS = 5_000L
        const val PREFS = "speed_prefs"
        const val KEY_MARKS = "marks" // vitesses notées par l'utilisateur, "12.3;45.6;..."
    }

    private lateinit var lm: LocationManager
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.deleteNotificationChannel(OLD_CHANNEL_ID)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Vitesse", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MARK -> {
                markSpeed()
                return START_STICKY
            }
        }

        lastError = null
        try {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
            // Fix toutes les secondes, sans filtre de distance.
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            running = true
        } catch (e: Exception) {
            lastError = "Démarrage impossible : ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        lastSpeedKmh = null
        onUpdate?.invoke()
        lm.removeUpdates(this)
        super.onDestroy()
    }

    override fun onLocationChanged(location: Location) {
        lastFixAt = SystemClock.elapsedRealtime()
        lastSpeedKmh = if (location.hasSpeed()) location.speed * 3.6f else null
        nm.notify(NOTIF_ID, buildNotification())
        onUpdate?.invoke()
    }

    @Deprecated("Requis sur les anciennes API")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        lastSpeedKmh = null
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun markSpeed() {
        val speed = currentSpeed() ?: return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_MARKS, "") ?: ""
        val updated = if (existing.isEmpty()) "%.1f".format(speed) else "$existing;${"%.1f".format(speed)}"
        prefs.edit().putString(KEY_MARKS, updated).apply()
        nm.notify(NOTIF_ID, buildNotification())
        onUpdate?.invoke()
    }

    private fun lowestMark(): Float? = marks(this).minOrNull()

    private fun buildNotification(): Notification {
        val speed = currentSpeed()
        val label = speed?.roundToInt()?.toString() ?: "--"
        val threshold = lowestMark()

        val text = buildString {
            append(if (speed != null) "$label km/h" else "Pas de signal GPS")
            if (threshold != null) append(" · seuil : ${threshold.roundToInt()} km/h")
        }

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val markSpeed = PendingIntent.getService(
            this, 1, Intent(this, SpeedService::class.java).setAction(ACTION_MARK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, SpeedService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(speedIcon(label))
            .setContentTitle(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, "Noter la vitesse", markSpeed).build())
            .addAction(Notification.Action.Builder(null, "Arrêter", stop).build())
            .build()
    }

    /** Dessine le nombre dans un bitmap utilisé comme icône de la barre d'état. */
    private fun speedIcon(label: String): Icon {
        val size = (24 * resources.displayMetrics.density).roundToInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            // Plus le texte est long, plus on réduit la police.
            textSize = size * when (label.length) {
                1, 2 -> 0.85f
                else -> 0.62f
            }
        }
        // Centre vertical du texte.
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(label, size / 2f, y, paint)
        return Icon.createWithBitmap(bmp)
    }
}
