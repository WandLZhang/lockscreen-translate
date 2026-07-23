package com.wz.lockscreentranslate

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Quick Settings tile — the reliable lock-screen launch. From a locked phone: swipe down, tap the
 * "Translate" tile → [TranslateActivity] opens straight over the keyguard (it's showWhenLocked).
 * Replaces the volume-chord (which needed an intrusive media session to hear keys while locked).
 */
class TranslateTile : TileService() {
    override fun onClick() {
        super.onClick()
        val i = Intent(this, TranslateActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}
