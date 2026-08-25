package com.stastyle.localsummarizer.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.stastyle.localsummarizer.MainActivity
import com.stastyle.localsummarizer.R
import com.stastyle.localsummarizer.appContainer
import com.stastyle.localsummarizer.domain.PipelineState
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs the transcription/summarization pipeline with a persistent notification
 * so Android does not kill the process during long (multi-minute) inference.
 */
class ProcessingService : Service() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("pipeline"),
    )
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                PipelineManager.requestCancel()
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val audioUri = intent.getStringExtra(EXTRA_AUDIO_URI)?.let(Uri::parse)
        val audioName = intent.getStringExtra(EXTRA_AUDIO_NAME).orEmpty()
        if (audioUri == null || job?.isActive == true) {
            if (audioUri == null) stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithNotification(getString(R.string.stage_decoding))
        acquireWakeLock()

        job = scope.launch {
            val settings = appContainer().settingsRepository.current()
            launch { observeStateForNotification() }
            MeetingPipeline(
                context = applicationContext,
                settings = settings,
                audioUri = audioUri,
                audioName = audioName,
            ).run()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun observeStateForNotification() {
        PipelineManager.state.collect { state ->
            if (!state.isRunning) return@collect
            updateNotification(stageLabel(state))
        }
    }

    private fun stageLabel(state: PipelineState): String = when (state) {
        is PipelineState.Decoding -> getString(R.string.stage_decoding)
        is PipelineState.LoadingWhisper -> getString(R.string.stage_loading_whisper)
        is PipelineState.Transcribing -> getString(R.string.stage_transcribing, state.percent)
        is PipelineState.LoadingLlama -> getString(R.string.stage_loading_llama)
        is PipelineState.Summarizing ->
            if (state.chunkCount > 1) {
                getString(R.string.stage_summarizing_chunk, state.chunkIndex + 1, state.chunkCount)
            } else {
                getString(R.string.stage_summarizing)
            }
        else -> getString(R.string.stage_done)
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ProcessingService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.cancel), cancelIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startForegroundWithNotification(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalSummarizer:pipeline")
            .apply { acquire(6 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.stastyle.localsummarizer.action.START"
        const val ACTION_CANCEL = "com.stastyle.localsummarizer.action.CANCEL"
        private const val EXTRA_AUDIO_URI = "audio_uri"
        private const val EXTRA_AUDIO_NAME = "audio_name"
        private const val CHANNEL_ID = "processing"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context, audioUri: Uri, audioName: String) {
            val intent = Intent(context, ProcessingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_AUDIO_URI, audioUri.toString())
                .putExtra(EXTRA_AUDIO_NAME, audioName)
            context.startForegroundService(intent)
        }
    }
}
