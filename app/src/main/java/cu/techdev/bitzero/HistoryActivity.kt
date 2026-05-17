package cu.techdev.bitzero

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cu.techdev.bitzero.databinding.ActivityHistoryBinding
import cu.techdev.bitzero.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var b: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = HistoryAdapter(::onOpen, ::onResume, ::onDelete)
        b.recyclerHistory.layoutManager = LinearLayoutManager(this)
        b.recyclerHistory.adapter = adapter

        b.btnClearHistory.setOnClickListener {
            HistoryStore.clear(this)
            refresh()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = HistoryStore.list(this)
        adapter.submit(list)
        b.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onOpen(e: HistoryEntry) {
        val uri = e.uri ?: return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(intent) } catch (_: Exception) {}
    }

    private fun onResume(e: HistoryEntry) {
        // Reanudar = volver a lanzar el servicio con la misma URL y mismo entryId.
        // El downloader detecta partes ya descargadas en cache y las omite.
        val intent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra(DownloadService.EXTRA_URL, e.originalUrl)
            putExtra(DownloadService.EXTRA_ENTRY_ID, e.id)
        }
        ContextCompat.startForegroundService(this, intent)
        finish() // Vuelve a la actividad principal para ver progreso
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun onDelete(e: HistoryEntry) {
        HistoryStore.delete(this, e.id)
        refresh()
    }
}

class HistoryAdapter(
    val onOpen: (HistoryEntry) -> Unit,
    val onResume: (HistoryEntry) -> Unit,
    val onDelete: (HistoryEntry) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val items = mutableListOf<HistoryEntry>()
    private val dateFmt = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())

    fun submit(list: List<HistoryEntry>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.b.tvName.text = e.name
        h.b.tvInfo.text = "${humanSize(e.size)}  •  ${dateFmt.format(Date(e.createdAt))}"
        when (e.state) {
            "completed" -> {
                h.b.tvState.text = "✓ ${e.savedPath ?: ""}"
                h.b.tvState.setTextColor(0xFF5BD68B.toInt())
                h.b.btnOpen.visibility = View.VISIBLE
                h.b.btnResume.visibility = View.GONE
            }
            "failed" -> {
                h.b.tvState.text = "✗ ${e.errorMessage ?: "Error"}"
                h.b.tvState.setTextColor(0xFFFF6B6B.toInt())
                h.b.btnOpen.visibility = View.GONE
                h.b.btnResume.visibility = View.VISIBLE
            }
            else -> {
                h.b.tvState.text = "⟳ En progreso…"
                h.b.tvState.setTextColor(0xFF7C9CFF.toInt())
                h.b.btnOpen.visibility = View.GONE
                h.b.btnResume.visibility = View.GONE
            }
        }
        h.b.btnOpen.setOnClickListener { onOpen(e) }
        h.b.btnResume.setOnClickListener { onResume(e) }
        h.b.btnDelete.setOnClickListener { onDelete(e) }
    }

    private fun humanSize(n: Long): String {
        if (n < 1024) return "$n B"
        val kb = n / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    class VH(val b: ItemHistoryBinding) : RecyclerView.ViewHolder(b.root)
}
