package com.wz.lockscreentranslate

import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * Full-screen landscape translator. MVP: type English -> proxy SSE -> WebView renders
 * Mandarin (top) + Cantonese (bottom) with the bundled fonts. "🌐 Verify" re-runs the same
 * phrase with forced web-grounding WITHOUT blanking the current result. Later increments add
 * showWhenLocked launch (attrs already set), voice input, and the volume-chord trigger.
 */
class TranslateActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var input: EditText
    private lateinit var goBtn: Button
    private lateinit var verifyBtn: Button
    private var ready = false
    private var pending: String? = null
    private var lastInput: String = ""      // the phrase currently shown — what "Verify" re-runs
    private var inFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        setContentView(R.layout.activity_translate)

        webView = findViewById(R.id.web)
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
                pending?.let { translate(it, web = false); pending = null }
            }
        }
        webView.loadUrl("file:///android_asset/render.html")

        input = findViewById(R.id.input)
        goBtn = findViewById(R.id.go)
        verifyBtn = findViewById(R.id.verify)
        goBtn.setOnClickListener { submit() }
        verifyBtn.setOnClickListener {
            if (lastInput.isNotEmpty() && !inFlight) translate(lastInput, web = true)
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submit(); true
            } else false
        }
    }

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (!ready) { pending = text; return }
        translate(text, web = false)
    }

    /**
     * One translation call. web=false (Go): shows "translating…" then streams live. web=true
     * (Verify): keeps the CURRENT result on screen, floats a "verifying…" badge, and only swaps
     * in the grounded result when the final generation arrives — the screen never goes blank.
     */
    private fun translate(text: String, web: Boolean) {
        if (inFlight) return
        inFlight = true
        lastInput = text
        setButtons(false)
        if (web) js("setVerifying(true)") else js("setBusy()")

        val url = Prefs.proxyUrl(this)
        val token = Prefs.authToken(this)
        val render = Prefs.renderMode(this)
        Thread {
            val sb = StringBuilder()
            TranslateClient.stream(
                proxyUrl = url, authToken = token, input = text, render = render, web = web,
                // On Verify we deliberately DON'T paint chunks — the old translation stays put
                // until the whole grounded result is ready. On Go we stream live as before.
                onChunk = {
                    sb.append(it)
                    if (!web) runOnUiThread { js("setContent(${JSONObject.quote(sb.toString())})") }
                },
                onDone = { content, _ ->
                    runOnUiThread { js("setContent(${JSONObject.quote(content)})"); finishFlight() }
                },
                onError = { e ->
                    // Verify failed: keep whatever is on screen; only a normal Go surfaces the error.
                    runOnUiThread {
                        if (web) js("setVerifying(false)") else js("setError(${JSONObject.quote(e)})")
                        finishFlight()
                    }
                },
            )
        }.start()
    }

    private fun finishFlight() {
        inFlight = false
        js("setVerifying(false)")
        setButtons(true)
    }

    private fun setButtons(enabled: Boolean) {
        goBtn.isEnabled = enabled
        verifyBtn.isEnabled = enabled && lastInput.isNotEmpty()  // only meaningful once translated
    }

    private fun js(code: String) = webView.evaluateJavascript(code, null)
}
