package org.takopi.percept.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService

/**
 * §3.7 foreground service: recording only ever runs with a visible
 * camera|microphone FGS notification (Android 14 compliant).
 */
class PerceptRecordingService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        val controller = PerceptRuntime.controller(this)
        if (controller.state.value.running) return
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        // Factory runs on the controller's background scope: rig construction
        // loads models and stages the whisper weights, which must not touch
        // the main thread.
        controller.startSession {
            CameraMicrophoneRig(applicationContext, this, controller::reportRigError)
        }
    }

    private fun stopRecording() {
        PerceptRuntime.controller(this).stopSession {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Percept recording",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, PerceptRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Percept is recording")
            .setContentText("Camera and microphone perception is running")
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "org.takopi.percept.action.START_RECORDING"
        const val ACTION_STOP = "org.takopi.percept.action.STOP_RECORDING"
        const val CHANNEL_ID = "percept-recording"
        const val NOTIFICATION_ID = 0x9E4C

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, PerceptRecordingService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, PerceptRecordingService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
