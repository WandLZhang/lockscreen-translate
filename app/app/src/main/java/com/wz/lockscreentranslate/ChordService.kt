package com.wz.lockscreentranslate

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.VolumeProviderCompat

/**
 * Foreground service that catches a volume DOUBLE-TAP while the phone is locked / screen-off and
 * launches [TranslateActivity] straight over the keyguard.
 *
 * How it hears volume keys while locked: it holds an active [MediaSessionCompat]; when armed it
 * switches the session to REMOTE volume via a [VolumeProviderCompat], so the system routes volume
 * key presses to [VolumeProviderCompat.onAdjustVolume] (the standard media-button path that works
 * on the lock screen) instead of changing the stream directly.
 *
 * Why arm only when locked: while the screen is on & unlocked the session stays on LOCAL playback,
 * so volume keys behave 100% normally and a double-tap can never fire by accident during use. The
 * chord is live exactly when you'd summon it — phone in hand, screen off/locked.
 *
 * Caveats (inherent to the only lock-screen-capable approach): it needs an ongoing (minimal)
 * notification, and a media app that is actively playing owns the volume-key routing until the
 * next press re-arms ours. See README.
 */
class ChordService : Service() {

    private lateinit var session: MediaSessionCompat
    private lateinit var audio: AudioManager
    private lateinit var keyguard: KeyguardManager
    private var armed = false
    private var lastTapAt = 0L

    private val volumeProvider =
        object : VolumeProviderCompat(VOLUME_CONTROL_RELATIVE, MAX_VOL, MID_VOL) {
            override fun onAdjustVolume(direction: Int) {
                if (direction == 0) return                        // key released, not a press
                // Apply the real volume change so a single tap isn't a dead key, then reset our
                // reported level to mid so there's always headroom to report the next press.
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                currentVolume = MID_VOL
                val wantUp = Prefs.chordKey(this@ChordService) == "up"
                if ((wantUp && direction > 0) || (!wantUp && direction < 0)) onChordTap()
            }
        }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> setArmed(true)
                Intent.ACTION_USER_PRESENT -> setArmed(false)
                Intent.ACTION_SCREEN_ON -> if (!keyguard.isKeyguardLocked) setArmed(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)

        session = MediaSessionCompat(this, "lt-chord").apply {
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1f)
                    .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE).build())
            isActive = true
        }
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED)
        setArmed(keyguard.isKeyguardLocked)   // arm immediately if starting on a locked screen
        Log.i(TAG, "ChordService created; key=${Prefs.chordKey(this)} armed=$armed")
    }

    private fun setArmed(on: Boolean) {
        if (on == armed || !::session.isInitialized) return
        armed = on
        if (on) session.setPlaybackToRemote(volumeProvider)
        else session.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
        Log.i(TAG, "armed=$on")
    }

    private fun onChordTap() {
        val now = SystemClock.elapsedRealtime()
        if (lastTapAt != 0L && now - lastTapAt <= WINDOW_MS) {
            lastTapAt = 0L
            Log.i(TAG, "chord! launching translator")
            startActivity(
                Intent(this, TranslateActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } else {
            lastTapAt = now
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        if (::session.isInitialized) session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Lock-screen trigger",
                        NotificationManager.IMPORTANCE_MIN))
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Translate is armed")
            .setContentText("Double-tap volume while locked to open")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(tap)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.wz.lockscreentranslate.STOP_CHORD"
        private const val TAG = "ChordService"
        private const val CHANNEL = "lt-chord"
        private const val NOTIF_ID = 42
        private const val WINDOW_MS = 400L   // two taps within this window = the chord
        private const val MAX_VOL = 100
        private const val MID_VOL = 50

        fun start(c: Context) {
            val i = Intent(c, ChordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i)
            else c.startService(i)
        }

        fun stop(c: Context) {
            c.startService(Intent(c, ChordService::class.java).setAction(ACTION_STOP))
        }
    }
}
