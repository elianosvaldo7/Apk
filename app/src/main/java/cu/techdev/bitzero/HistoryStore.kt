package cu.techdev.bitzero

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val id: String,
    val name: String,
    val size: Long,
    val createdAt: Long,
    val state: String,             // "completed" | "failed" | "in_progress"
    val savedPath: String?,        // "Download/BitZero/foo.pdf"
    val uri: String?,              // content://... para abrir
    val originalUrl: String,
    val errorMessage: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("size", size)
        put("createdAt", createdAt)
        put("state", state)
        put("savedPath", savedPath ?: JSONObject.NULL)
        put("uri", uri ?: JSONObject.NULL)
        put("originalUrl", originalUrl)
        put("errorMessage", errorMessage ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject) = HistoryEntry(
            id = o.getString("id"),
            name = o.getString("name"),
            size = o.optLong("size", 0L),
            createdAt = o.optLong("createdAt", 0L),
            state = o.optString("state", "completed"),
            savedPath = o.optStringOrNull("savedPath"),
            uri = o.optStringOrNull("uri"),
            originalUrl = o.optString("originalUrl", ""),
            errorMessage = o.optStringOrNull("errorMessage")
        )
        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key) || !has(key)) null else optString(key)
    }
}

object HistoryStore {

    private const val PREFS = "bitzero_history"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 200

    fun list(context: Context): List<HistoryEntry> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = ArrayList<HistoryEntry>(arr.length())
        for (i in 0 until arr.length()) out.add(HistoryEntry.fromJson(arr.getJSONObject(i)))
        return out.sortedByDescending { it.createdAt }
    }

    fun upsert(context: Context, entry: HistoryEntry) {
        val current = list(context).toMutableList()
        val idx = current.indexOfFirst { it.id == entry.id }
        if (idx >= 0) current[idx] = entry else current.add(0, entry)
        save(context, current.take(MAX_ENTRIES))
    }

    fun delete(context: Context, id: String) {
        val current = list(context).filterNot { it.id == id }
        save(context, current)
    }

    fun clear(context: Context) {
        save(context, emptyList())
    }

    private fun save(context: Context, entries: List<HistoryEntry>) {
        val arr = JSONArray()
        for (e in entries) arr.put(e.toJson())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    fun newId(): String = "dl_${System.currentTimeMillis()}_${(0..9999).random()}"
}
