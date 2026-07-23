package com.wz.lockscreentranslate

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * Translator. Input is the WebView's editable text driven by Gboard (its voice typing is steadier
 * than the native recognizer, which we dropped). Opens portrait with the keyboard up and the
 * ambient wave behind the big auto-fitting text; on a result it rotates to landscape for the two
 * columns. configChanges keeps the WebView alive across the rotation (no reload).
 */
class TranslateActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var inFlight = false
    private var lastInput = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        }
        setContentView(R.layout.activity_translate)
        if (Prefs.authToken(this).isBlank()) startActivity(Intent(this, SettingsActivity::class.java))

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT   // input starts portrait

        webView = findViewById(R.id.web)
        webView.setBackgroundColor(Color.BLACK)
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false   // TTS plays after its async fetch
        }
        webView.addJavascriptInterface(Bridge(), "Android")
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { raiseKeyboard() }
        }
        webView.loadUrl("file:///android_asset/render.html")
    }

    /** JS -> Kotlin (methods arrive on a binder thread; hop to UI). */
    inner class Bridge {
        @JavascriptInterface fun translateText(text: String) { runOnUiThread { translate(text, web = false) } }
        @JavascriptInterface fun verify() { runOnUiThread { if (lastInput.isNotEmpty()) translate(lastInput, web = true) } }
        @JavascriptInterface fun openSettings() { runOnUiThread { startActivity(Intent(this@TranslateActivity, SettingsActivity::class.java)) } }
        @JavascriptInterface fun focusInput() { runOnUiThread { raiseKeyboard() } }
        @JavascriptInterface fun orient(mode: String) {
            runOnUiThread {
                requestedOrientation =
                    if (mode == "landscape") ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
    }

    /** Focus the editable and raise Gboard (best-effort; some devices still need one tap). */
    private fun raiseKeyboard() {
        webView.requestFocus()
        js("var c=document.getElementById('compose'); if(c) c.focus();")
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        webView.postDelayed({ imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT) }, 300)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(webView.windowToken, 0)
        webView.clearFocus()
    }

    /**
     * web=false: JS already shows the 'sending' dots; we stream the result in. web=true (Verify):
     * keep the current result on screen (globe → dots) and swap only when the grounded result lands.
     */
    private fun translate(text: String, web: Boolean) {
        if (inFlight) return
        inFlight = true
        lastInput = text
        hideKeyboard()                      // clear Gboard off the sending/result view
        if (web) js("setVerifying(true)")
        val url = Prefs.proxyUrl(this); val token = Prefs.authToken(this); val render = Prefs.renderMode(this)
        Thread {
            val sb = StringBuilder()
            TranslateClient.stream(
                proxyUrl = url, authToken = token, input = text, render = render, web = web,
                onChunk = { sb.append(it); runOnUiThread { js("setContent(${q(sb.toString())})") } },
                onDone = { content, _ -> runOnUiThread { js("setContent(${q(content)})"); finishFlight() } },
                onError = { e -> runOnUiThread {
                    if (web) js("setVerifying(false)") else js("setError(${q(e)})")
                    finishFlight()
                } },
            )
        }.start()
    }

    private fun finishFlight() { inFlight = false; js("setVerifying(false)") }

    private fun q(s: String) = JSONObject.quote(s)
    private fun js(code: String) = webView.evaluateJavascript(code, null)
}
