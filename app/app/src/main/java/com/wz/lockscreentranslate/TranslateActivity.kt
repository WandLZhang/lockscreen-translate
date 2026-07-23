package com.wz.lockscreentranslate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.Locale

/**
 * Full-screen landscape, voice-first translator. Opens straight into listening (mic hot), streams
 * the translation to a black WebView (render.html) that shows Mandarin | Cantonese in two equal
 * columns and reveals per-word pronunciation on tap. The living wave-ring in the WebView is the
 * control; it calls back here through the `Android` JS bridge to drive SpeechRecognizer.
 */
class TranslateActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var recognizer: SpeechRecognizer? = null
    private var ready = false
    private var listening = false
    private var inFlight = false
    private var lastInput = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        }
        setContentView(R.layout.activity_translate)

        webView = findViewById(R.id.web)
        webView.setBackgroundColor(Color.BLACK)
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = true
            domStorageEnabled = true          // Cache API / storage for the dict
        }
        webView.addJavascriptInterface(Bridge(), "Android")
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
                maybeStartVoice()             // auto-listen the moment we're up
            }
        }
        webView.loadUrl("file:///android_asset/render.html")
    }

    /** JS -> Kotlin. Methods arrive on a binder thread; hop to the UI thread. */
    inner class Bridge {
        @JavascriptInterface fun startVoice() { runOnUiThread { maybeStartVoice() } }
        @JavascriptInterface fun stopVoice() { runOnUiThread { this@TranslateActivity.stopVoice() } }
        @JavascriptInterface fun translateText(text: String) { runOnUiThread { translate(text, web = false) } }
        @JavascriptInterface fun verify() { runOnUiThread { if (lastInput.isNotEmpty()) translate(lastInput, web = true) } }
    }

    private fun maybeStartVoice() {
        if (inFlight) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        startVoice()
    }

    private fun startVoice() {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("onVoiceError(${q("Speech input unavailable — tap ⌨ to type")})"); return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listener) }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())   // any language = device locale
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        listening = true
        recognizer?.startListening(intent)
    }

    private fun stopVoice() {
        listening = false
        recognizer?.stopListening()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = js("onListening()")
        override fun onRmsChanged(rms: Float) {
            val norm = ((rms + 2f) / 12f).coerceIn(0f, 1f)     // dB-ish -> 0..1 for the waveform
            js("onAmplitude($norm)")
        }
        override fun onPartialResults(b: Bundle?) {
            first(b)?.let { js("onPartial(${q(it)})") }
        }
        override fun onResults(b: Bundle?) {
            listening = false
            val text = first(b)?.trim().orEmpty()
            if (text.isEmpty()) js("onVoiceError(${q("Didn’t catch that — tap to try again")})")
            else { js("onHeard(${q(text)})"); translate(text, web = false) }
        }
        override fun onError(error: Int) {
            listening = false
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "Didn’t catch that — tap to try again"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission needed"
                else -> "Tap to try again"
            }
            js("onVoiceError(${q(msg)})")
        }
        override fun onBeginningOfSpeech() {}
        override fun onEndOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun first(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    /**
     * web=false (voice/typed): show "translating…" then stream in. web=true (Verify): keep the
     * current result on screen under the badge and only swap when the grounded result arrives.
     */
    private fun translate(text: String, web: Boolean) {
        if (inFlight) return
        inFlight = true
        lastInput = text
        stopVoice()
        if (web) js("setVerifying(true)") else js("setThinking()")

        val url = Prefs.proxyUrl(this); val token = Prefs.authToken(this); val render = Prefs.renderMode(this)
        Thread {
            val sb = StringBuilder()
            TranslateClient.stream(
                proxyUrl = url, authToken = token, input = text, render = render, web = web,
                onChunk = { sb.append(it); if (!web) runOnUiThread { js("setContent(${q(sb.toString())})") } },
                onDone = { content, _ -> runOnUiThread { js("setContent(${q(content)})"); finishFlight() } },
                onError = { e -> runOnUiThread {
                    if (web) js("setVerifying(false)") else js("setError(${q(e)})")
                    finishFlight()
                } },
            )
        }.start()
    }

    private fun finishFlight() { inFlight = false; js("setVerifying(false)") }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc == REQ_MIC) {
            if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoice()
            else js("onVoiceError(${q("Enable mic in Settings, or tap ⌨ to type")})")
        }
    }

    override fun onDestroy() { recognizer?.destroy(); super.onDestroy() }

    private fun q(s: String) = JSONObject.quote(s)
    private fun js(code: String) = webView.evaluateJavascript(code, null)

    companion object { private const val REQ_MIC = 11 }
}
