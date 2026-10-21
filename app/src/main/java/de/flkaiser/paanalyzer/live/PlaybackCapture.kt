package de.flkaiser.paanalyzer.live

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import de.flkaiser.paanalyzer.MainActivity
import de.flkaiser.paanalyzer.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Capturing what the phone plays (Android 10+): the user allows it in the system's
 * "start recording or casting" dialog, then [PlaybackCaptureService] holds the permission
 * (a [MediaProjection]) while the live analyzer reads the audio through it.
 */
object PlaybackCapture {
    /** Android 10 added capturing other apps' audio. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private val current = MutableStateFlow<MediaProjection?>(null)

    /** The granted capture, null when none is running. */
    val projection: StateFlow<MediaProjection?> = current.asStateFlow()

    internal fun publish(projection: MediaProjection?) {
        current.value = projection
    }

    /** The system dialog asking the user to allow the capture (the whole screen, which includes all apps' audio). */
    fun consentIntent(context: Context): Intent {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
    }

    /** Starts the capture with the result of [consentIntent]. */
    fun start(context: Context, resultCode: Int, data: Intent) {
        val intent = Intent(context, PlaybackCaptureService::class.java)
            .putExtra(PlaybackCaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(PlaybackCaptureService.EXTRA_DATA, data)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, PlaybackCaptureService::class.java))
    }
}

/**
 * Foreground service that keeps the phone-audio capture permission while the live analyzer uses it
 * (Android requires one for screen and audio capture). It shows a notification until stopped.
 */
class PlaybackCaptureService : Service() {

    private var projection: MediaProjection? = null

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Stopped by the user (status bar) or the system.
            projection = null
            PlaybackCapture.publish(null)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14: the foreground service must run before the capture permission is used.
        goForeground()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        release()
        val granted = runCatching { getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data) }.getOrNull()
        if (granted == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        granted.registerCallback(callback, Handler(Looper.getMainLooper()))
        projection = granted
        PlaybackCapture.publish(granted)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        release()
        PlaybackCapture.publish(null)
        super.onDestroy()
    }

    private fun release() {
        projection?.let {
            it.unregisterCallback(callback)
            it.stop()
        }
        projection = null
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Phone audio capture", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the live analyzer listens to what the phone plays"
            },
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_live)
            .setContentTitle("Analysing phone audio")
            .setContentText("The live analyzer shows the spectrum of what this phone plays.")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 1
    }
}
