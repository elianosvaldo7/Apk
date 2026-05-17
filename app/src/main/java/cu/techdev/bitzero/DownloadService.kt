package cu.techdev.bitzero

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servicio foreground que ejecuta descargas en segundo plano con notificación persistente.
 * Soporta múltiples descargas simultáneas (concurrentes), aunque la UI inicia de a una.
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "cu.techdev.bitzero.action.START"
        const val ACTION_CANCEL = "cu.techdev.bitzero.action.CANCEL"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_ENTRY_ID = "extra_entry_id"
        const val CHANNEL_ID = "bitzero_downloads"
        const val NOTIFICATION_ID_BASE = 1000

        // Listeners en memoria para que la Activity reciba updates en vivo
        interface Listener {
            fun onUpdate(entryId: String, state: DownloadState)
        }
        val listeners = mutableSetOf<Listener>()
    }

    data class DownloadState(
        val entryId: String,
        val name: String,
        val statusText: String,
        val downloaded: Long,
        val total: Long,
        val finished: Boolean = false,
        val success: Boolean = false,
        val errorMessage: String? = null,
        val resultUri: Uri? = null,
        val resultPath: String? = null
    )

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val notifIdMap = ConcurrentHashMap<String, Int>()
    private var notifSeq = NOTIFICATION_ID_BASE
    private val scope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    inner class LocalBinder : Binder() { fun getService() = this@DownloadService }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val entryId = intent.getStringExtra(EXTRA_ENTRY_ID) ?: HistoryStore.newId()
                startDownload(entryId, url)
            }
            ACTION_CANCEL -> {
                val entryId = intent.getStringExtra(EXTRA_ENTRY_ID) ?: return START_NOT_STICKY
                cancelFlags[entryId]?.set(true)
            }
        }
        return START_STICKY
    }

    private fun startDownload(entryId: String, url: String) {
        val params = try {
            BitZeroUrl.parse(url)
        } catch (e: Exception) {
            broadcast(DownloadState(entryId, "URL inválida", "Error: ${e.message}", 0, 0, finished = true, success = false, errorMessage = e.message))
            return
        }

        val notifId = notifIdMap.getOrPut(entryId) { ++notifSeq }
        val initialEntry = HistoryEntry(
            id = entryId, name = params.filename, size = params.fileSize,
            createdAt = System.currentTimeMillis(), state = "in_progress",
            savedPath = null, uri = null, originalUrl = url
        )
        HistoryStore.upsert(this, initialEntry)

        startForeground(notifId, buildNotification(entryId, params.filename, "Iniciando…", 0, 100))

        val cancel = AtomicBoolean(false)
        cancelFlags[entryId] = cancel

        val job = scope.launch {
            val downloader = Downloader(applicationContext, params, skipLogin = false) { cancel.get() }
            downloader.run(object : Downloader.Progress {
                @Volatile var lastPercent = -1
                @Volatile var lastUpdateMs = 0L
                override fun onStatus(message: String) {
                    val state = DownloadState(entryId, params.filename, message, 0, params.fileSize)
                    broadcast(state)
                    updateNotification(entryId, params.filename, message, 0, 100, indeterminate = true, notifId = notifId)
                }
                override fun onProgress(downloaded: Long, total: Long) {
                    val pct = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                    val now = System.currentTimeMillis()
                    val state = DownloadState(entryId, params.filename,
                        "Descargando… ${humanSize(downloaded)} / ${humanSize(total)}", downloaded, total)
                    broadcast(state)
                    if (pct != lastPercent && now - lastUpdateMs > 500) {
                        updateNotification(entryId, params.filename, "$pct%  ${humanSize(downloaded)} / ${humanSize(total)}",
                            pct, 100, indeterminate = false, notifId = notifId)
                        lastPercent = pct; lastUpdateMs = now
                    }
                }
                override fun onCompleted(uri: Uri, filePath: String) {
                    val state = DownloadState(entryId, params.filename, "Completado", params.fileSize, params.fileSize,
                        finished = true, success = true, resultUri = uri, resultPath = filePath)
                    broadcast(state)
                    HistoryStore.upsert(this@DownloadService, initialEntry.copy(
                        state = "completed", savedPath = filePath, uri = uri.toString()
                    ))
                    completedNotification(notifId, params.filename, filePath, uri)
                    finishOne(entryId)
                }
                override fun onFailed(reason: String) {
                    val state = DownloadState(entryId, params.filename, "Error: $reason", 0, params.fileSize,
                        finished = true, success = false, errorMessage = reason)
                    broadcast(state)
                    HistoryStore.upsert(this@DownloadService, initialEntry.copy(
                        state = "failed", errorMessage = reason
                    ))
                    failedNotification(notifId, params.filename, reason)
                    finishOne(entryId)
                }
            })
        }
        activeJobs[entryId] = job
    }

    private fun broadcast(state: DownloadState) {
        synchronized(listeners) {
            for (l in listeners.toList()) l.onUpdate(state.entryId, state)
        }
    }

    private fun finishOne(entryId: String) {
        activeJobs.remove(entryId)
        cancelFlags.remove(entryId)
        if (activeJobs.isEmpty()) {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            mgr.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(entryId: String, title: String, text: String, progress: Int, max: Int, indeterminate: Boolean = true): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val cancelIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_ENTRY_ID, entryId)
        }
        val cancelPi = PendingIntent.getService(this, entryId.hashCode(), cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(max, progress, indeterminate)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.action_cancel), cancelPi)
            .build()
    }

    private fun updateNotification(entryId: String, title: String, text: String, progress: Int, max: Int, indeterminate: Boolean, notifId: Int) {
        val n = buildNotification(entryId, title, text, progress, max, indeterminate)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(notifId, n)
    }

    private fun completedNotification(notifId: Int, name: String, path: String, uri: Uri) {
        val openIntent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val pi = PendingIntent.getActivity(this, notifId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("✓ $name")
            .setContentText(path)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(notifId, n)
    }

    private fun failedNotification(notifId: Int, name: String, reason: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("✗ $name")
            .setContentText("Error: $reason")
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(notifId, n)
    }

    private fun humanSize(n: Long): String {
        if (n < 1024) return "$n B"
        val kb = n / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }
}
