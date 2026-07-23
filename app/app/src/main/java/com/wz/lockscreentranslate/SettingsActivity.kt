package com.wz.lockscreentranslate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** Launcher screen: proxy URL + shared token, the lock-screen launch button, open the translator. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Volume-chord shelved (its MediaSession forced a cast bar + didn't fire). Stop any leftover.
        ChordService.stop(this)
        Prefs.setChordEnabled(this, false)

        val url = findViewById<EditText>(R.id.proxyUrl)
        val token = findViewById<EditText>(R.id.authToken)
        url.setText(Prefs.proxyUrl(this))
        token.setText(Prefs.authToken(this))

        findViewById<Button>(R.id.save).setOnClickListener {
            Prefs.save(this, url.text.toString(), token.text.toString())
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.open).setOnClickListener {
            Prefs.save(this, url.text.toString(), token.text.toString())
            startActivity(Intent(this, TranslateActivity::class.java))
        }

        val notif = findViewById<SwitchCompat>(R.id.launchNotif)
        notif.isChecked = Prefs.launchNotif(this)
        notif.setOnCheckedChangeListener { _, on ->
            Prefs.setLaunchNotif(this, on)
            if (on) { ensureNotifPerm(); LaunchNotif.post(this) } else LaunchNotif.cancel(this)
        }

        // Keep the lock-screen launcher fresh whenever Settings opens.
        if (Prefs.launchNotif(this)) { ensureNotifPerm(); LaunchNotif.post(this) }
    }

    private fun ensureNotifPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == REQ_NOTIF && r.firstOrNull() == PackageManager.PERMISSION_GRANTED && Prefs.launchNotif(this)) {
            LaunchNotif.post(this)
        }
    }

    companion object { private const val REQ_NOTIF = 9 }
}
