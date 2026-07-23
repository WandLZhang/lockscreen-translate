package com.wz.lockscreentranslate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * A persistent, low-priority notification that acts as a one-tap lock-screen launcher — tap it and
 * [TranslateActivity] opens over the keyguard. Reliable and keeps the Pixel power menu intact
 * (unlike remapping hold-power to Assistant). Re-posted whenever Settings opens; gone after reboot
 * until the app is opened again.
 */
object LaunchNotif {
    private const val CHANNEL = "lt-launch"
    private const val ID = 7

    fun post(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Lock-screen launch", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(
            c, 0,
            Intent(c, TranslateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("譯 Translate")
            .setContentText("Tap to translate")
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)   // show on the lock screen
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
        nm.notify(ID, n)
    }

    fun cancel(c: Context) {
        c.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}
