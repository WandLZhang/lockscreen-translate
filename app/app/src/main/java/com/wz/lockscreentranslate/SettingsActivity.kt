package com.wz.lockscreentranslate

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Launcher screen: configure the proxy URL + shared token, then open the translator. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Volume-chord is shelved (its MediaSession showed a persistent cast/volume bar and the
        // double-tap didn't fire reliably). Stop any instance left running by an older build.
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
    }
}
