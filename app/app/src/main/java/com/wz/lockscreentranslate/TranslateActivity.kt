package com.wz.lockscreentranslate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Translator. Input is the WebView's editable text driven by Gboard (its voice typing is steadier
 * than the native recognizer, which we dropped). Opens portrait with the keyboard up and the
 * ambient wave behind the big auto-fitting text; on a result it rotates to landscape for the two
 * columns. configChanges keeps the WebView alive across the rotation (no reload).
 */
class TranslateActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var lastInput = ""
    /** Bumped per submit/reset; late results from an older query are discarded. */
    private var generation = 0
    private var groundedLanded = false

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

        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        releaseWakeFlag()
    }

    /** Re-armed when the assist gesture re-launches us onto an existing instance. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setTurnScreenOn(true)
        resetToInput()
        releaseWakeFlag()
    }

    /**
     * turnScreenOn is only needed for the moment we're launched over a dark keyguard. If it stays
     * set, the window yanks the screen back on the instant you press power — which is why the power
     * button looked dead. Release it shortly after we're up so power behaves normally again.
     */
    private fun releaseWakeFlag() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        webView.postDelayed({ runCatching { setTurnScreenOn(false) } }, 1200)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOff) }
        super.onDestroy()
    }

    /**
     * Screen off (power button or timeout) -> clear to a fresh input straight away. Because the
     * activity is showWhenLocked it often isn't fully stopped by a screen-off, so onRestart alone
     * never fires and the stale result was still there on wake. Clearing while the screen is dark
     * also means no visible flash.
     */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = resetToInput()
    }

    /** Belt-and-braces for the ordinary background -> foreground return. */
    override fun onRestart() {
        super.onRestart()
        resetToInput()
    }

    private fun resetToInput() {
        generation++          // orphan any in-flight arms so they can't paint over a fresh input
        groundedLanded = false
        js("if(window.newInput){ newInput(); }")
    }

    /** JS -> Kotlin (methods arrive on a binder thread; hop to UI). */
    inner class Bridge {
        @JavascriptInterface fun translateText(text: String) { runOnUiThread { translate(text) } }
        @JavascriptInterface fun verify() { runOnUiThread { verifyAgain() } }
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
     * Verify-by-default without the wait: fire BOTH arms at once. The ungrounded answer streams in
     * at ~1.8s so you're never staring at a spinner; the web-grounded one silently replaces it a few
     * seconds later (dots show meanwhile). If grounding fails you simply keep the fast answer — and
     * on the queries where grounding is worse, you already saw the good one.
     */
    private fun translate(text: String) {
        val gen = ++generation
        groundedLanded = false
        lastInput = text
        hideKeyboard()
        js("setVerifying(true)")
        fire(text, gen, web = false)
        fire(text, gen, web = true)
    }

    /** Manual re-check of what's on screen (the 🌐 button). */
    private fun verifyAgain() {
        if (lastInput.isEmpty()) return
        js("setVerifying(true)")
        fire(lastInput, generation, web = true)
    }

    private fun fire(text: String, gen: Int, web: Boolean) {
        val url = Prefs.proxyUrl(this); val token = Prefs.authToken(this); val render = Prefs.renderMode(this)
        Thread {
            val sb = StringBuilder()
            TranslateClient.stream(
                proxyUrl = url, authToken = token, input = text, render = render, web = web,
                // Only the fast arm streams; the grounded one swaps in whole so the screen
                // never flickers mid-replacement.
                onChunk = {
                    sb.append(it)
                    if (!web) runOnUiThread {
                        if (gen == generation && !groundedLanded) js("setContent(${q(sb.toString())})")
                    }
                },
                onDone = { content, _ -> runOnUiThread {
                    if (gen != generation) return@runOnUiThread     // stale query, drop it
                    if (web) {
                        groundedLanded = true
                        js("setContent(${q(content)})")
                        js("setVerifying(false)")
                    } else if (!groundedLanded) js("setContent(${q(content)})")
                } },
                onError = { e -> runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    // Grounding failed: keep whatever the fast arm gave us, just stop the dots.
                    if (web) js("setVerifying(false)")
                    else if (!groundedLanded) js("setError(${q(e)})")
                } },
            )
        }.start()
    }

    private fun q(s: String) = JSONObject.quote(s)
    private fun js(code: String) = webView.evaluateJavascript(code, null)
}
