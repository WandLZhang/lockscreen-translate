package com.wz.lockscreentranslate

import android.service.voice.VoiceInteractionService

/** Entry point that lets the app be selected as the device Digital Assistant. The real work is in
 *  [AssistSessionService] / AssistSession — when the assist gesture fires (incl. from a locked,
 *  black screen) it launches [TranslateActivity] over the keyguard. */
class AssistService : VoiceInteractionService()
