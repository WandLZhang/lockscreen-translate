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
 * Mandarin (top) + Cantonese (bottom) with the bundled fonts. Later increments add
 * showWhenLocked launch (attrs already set), voice input, and the volume-chord trigger.
 */
class TranslateActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var input: EditText
    private var ready = false
    private var pending: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        setContentView(R.layout.activity_translate)

        web = findViewById(R.id.web)
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = true
        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
                pending?.let { translate(it); pending = null }
            }
        }
        web.loadUrl("file:///android_asset/render.html")

        input = findViewById(R.id.input)
        findViewById<Button>(R.id.go).setOnClickListener { submit() }
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
        translate(text)
    }

    private fun translate(text: String) {
        js("setBusy()")
        val url = Prefs.proxyUrl(this)
        val token = Prefs.authToken(this)
        val render = Prefs.renderMode(this)
        Thread {
            val sb = StringBuilder()
            TranslateClient.stream(
                proxyUrl = url, authToken = token, input = text, render = render,
                onChunk = { sb.append(it); runOnUiThread { js("setContent(${JSONObject.quote(sb.toString())})") } },
                onDone = { content, _ -> runOnUiThread { js("setContent(${JSONObject.quote(content)})") } },
                onError = { e -> runOnUiThread { js("setError(${JSONObject.quote(e)})") } },
            )
        }.start()
    }

    private fun js(code: String) = web.evaluateJavascript(code, null)
}
