package cu.techdev.bitzero

import android.Manifest
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import cu.techdev.bitzero.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), DownloadService.Companion.Listener {

    private lateinit var b: ActivityMainBinding
    private var currentEntryId: String? = null
    private var service: DownloadService? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as DownloadService.LocalBinder).getService()
            service = s
            synchronized(DownloadService.listeners) { DownloadService.listeners.add(this@MainActivity) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val requestNotifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        b.btnPaste.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val txt = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            if (txt.isNotEmpty()) b.inputUrl.setText(txt)
        }
        b.btnClear.setOnClickListener { b.inputUrl.setText("") }
        b.btnDownload.setOnClickListener { startDownload() }
        b.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        b.btnCancel.setOnClickListener {
            val id = currentEntryId ?: return@setOnClickListener
            val intent = Intent(this, DownloadService::class.java).apply {
                action = DownloadService.ACTION_CANCEL
                putExtra(DownloadService.EXTRA_ENTRY_ID, id)
            }
            startService(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, DownloadService::class.java), conn, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        synchronized(DownloadService.listeners) { DownloadService.listeners.remove(this) }
        try { unbindService(conn) } catch (_: Exception) {}
        super.onStop()
    }

    private fun startDownload() {
        val url = b.inputUrl.text.toString().trim()
        if (url.isEmpty()) {
            b.tvStatus.text = getString(R.string.invalid_url)
            return
        }
        // Validar formato antes de lanzar el servicio
        try { BitZeroUrl.parse(url) } catch (e: Exception) {
            b.progressCard.visibility = View.VISIBLE
            b.tvFileName.text = "—"
            b.tvStatus.text = "URL inválida: ${e.message}"
            return
        }

        val id = HistoryStore.newId()
        currentEntryId = id
        b.progressCard.visibility = View.VISIBLE
        b.tvFileName.text = "Preparando…"
        b.tvStatus.text = getString(R.string.status_parsing)
        b.progressBar.isIndeterminate = true
        b.tvProgress.text = "—"
        b.tvSize.text = "—"

        val intent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra(DownloadService.EXTRA_URL, url)
            putExtra(DownloadService.EXTRA_ENTRY_ID, id)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onUpdate(entryId: String, state: DownloadService.DownloadState) {
        if (entryId != currentEntryId) return
        runOnUiThread {
            b.tvFileName.text = state.name
            b.tvStatus.text = state.statusText
            if (state.total > 0) {
                val pct = ((state.downloaded * 100) / state.total).toInt().coerceIn(0, 100)
                b.progressBar.isIndeterminate = false
                b.progressBar.progress = pct
                b.tvProgress.text = "$pct%"
                b.tvSize.text = "${humanSize(state.downloaded)} / ${humanSize(state.total)}"
            } else {
                b.progressBar.isIndeterminate = true
            }
            if (state.finished) {
                b.progressBar.isIndeterminate = false
                if (state.success) {
                    b.progressBar.progress = 100
                    b.tvStatus.text = getString(R.string.status_completed) + " — ${state.resultPath}"
                } else {
                    b.tvStatus.text = getString(R.string.status_failed, state.errorMessage ?: "—")
                }
            }
        }
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
