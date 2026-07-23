package com.wz.lockscreentranslate

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Creates the assist session. */
class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/** When the assist gesture triggers, immediately open the translator over the keyguard and close
 *  the (invisible) assistant session — the app IS the "assistant" here. */
private class AssistSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val i = Intent(context, TranslateActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startAssistantActivity(i)   // launches correctly over the lock screen
        hide()
    }
}
