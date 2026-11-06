package io.nohzoh.speedbar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Écran minimal : permissions, démarrer/arrêter, voir et effacer les vitesses notées. */
class MainActivity : Activity() {

    private lateinit var marksView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Android 15 impose l'edge-to-edge : on décale le contenu sous les barres système.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(pad, bars.top + pad, pad, bars.bottom + pad)
                insets
            }
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setPadding(0, 0, 0, pad)
        }
        layout.addView(title)

        val start = Button(this).apply {
            text = "Démarrer"
            setOnClickListener { startWithPermissions() }
        }
        val stop = Button(this).apply {
            text = "Arrêter"
            setOnClickListener {
                startService(Intent(this@MainActivity, SpeedService::class.java)
                    .setAction(SpeedService.ACTION_STOP))
            }
        }
        marksView = TextView(this).apply { setPadding(0, pad, 0, pad) }
        val clear = Button(this).apply {
            text = "Effacer les vitesses notées"
            setOnClickListener {
                getSharedPreferences(SpeedService.PREFS, Context.MODE_PRIVATE)
                    .edit().remove(SpeedService.KEY_MARKS).apply()
                refreshMarks()
            }
        }

        layout.addView(start)
        layout.addView(stop)
        layout.addView(marksView)
        layout.addView(clear)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        refreshMarks()
    }

    private fun refreshMarks() {
        val raw = getSharedPreferences(SpeedService.PREFS, Context.MODE_PRIVATE)
            .getString(SpeedService.KEY_MARKS, "") ?: ""
        marksView.text = if (raw.isEmpty()) {
            "Aucune vitesse notée.\nAppuie sur « Noter la vitesse » dans la notification pour enregistrer un seuil (ex. : vitesse à partir de laquelle une app te bloque)."
        } else {
            "Vitesses notées (km/h) :\n" + raw.replace(";", " · ")
        }
    }

    private fun startWithPermissions() {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) {
            startForegroundService(Intent(this, SpeedService::class.java))
        } else {
            requestPermissions(needed.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, SpeedService::class.java))
        }
    }
}
