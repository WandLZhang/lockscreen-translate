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

/** Launcher screen: configure the proxy URL + shared token, arm the lock-screen chord, open the app. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

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

        val chordEnable = findViewById<SwitchCompat>(R.id.chordEnable)
        val chordUp = findViewById<SwitchCompat>(R.id.chordUp)
        chordEnable.isChecked = Prefs.chordEnabled(this)
        chordUp.isChecked = Prefs.chordKey(this) == "up"
        chordUp.setOnCheckedChangeListener { _, up -> Prefs.setChordKey(this, if (up) "up" else "down") }
        chordEnable.setOnCheckedChangeListener { _, on ->
            Prefs.setChordEnabled(this, on)
            if (on) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
                }
                ChordService.start(this)
                Toast.makeText(this, "Armed — double-tap volume while locked", Toast.LENGTH_SHORT).show()
            } else {
                ChordService.stop(this)
            }
        }
    }

    companion object { private const val REQ_NOTIF = 7 }
}
