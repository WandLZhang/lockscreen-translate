package com.wz.lockscreentranslate

import android.content.Intent
import android.speech.RecognitionService

/** Stub RecognitionService — required by the voice-interaction manifest wiring so the app is a
 *  valid assistant. We don't use it (TranslateActivity runs its own SpeechRecognizer). */
class AssistRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
