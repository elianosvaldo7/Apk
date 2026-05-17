package cu.techdev.bitzero

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Núcleo de descarga — replica el flujo de main() de bitzero.py:
 * 1. test connection
 * 2. login (a menos que skipLogin=true)
 * 3. descarga cada parte probando 6 patrones de URL
 * 4. reensambla y decodifica según el modo
 * 5. guarda en Download/BitZero/ (MediaStore en Android 10+)
 *
 * Soporta:
 * - callback de progreso global
 * - cancelación cooperativa vía isCancelled
 * - reanudación: si los .part_XXX existen en cacheDir, se omiten
 */
class Downloader(
    private val context: Context,
    private val params: BitZeroParams,
    private val skipLogin: Boolean = false,
    private val isCancelled: () -> Boolean = { false }
) {

    interface Progress {
        fun onStatus(message: String)
        fun onProgress(downloaded: Long, total: Long)
        fun onCompleted(uri: Uri, filePath: String)
        fun onFailed(reason: String)
    }

    private val tempDir: File by lazy {
        File(context.cacheDir, "bitzero_${params.submissionId}_${params.fileIds.joinToString("_").take(20)}").apply { mkdirs() }
    }

    fun run(progress: Progress) {
        try {
            val client = HttpClient.newSession()

            progress.onStatus(context.getString(R.string.status_connecting))
            if (!OjsLogin.testConnection(client, params.host)) {
                progress.onFailed("No se puede conectar a ${params.host}")
                return
            }

            if (!skipLogin) {
                progress.onStatus(context.getString(R.string.status_login))
                val token = try {
                    OjsLogin.login(client, params.host, params.username, params.password, params.contexto)
                } catch (e: Exception) {
                    progress.onFailed("Error de login: ${e.message}")
                    return
                }
                if (token == null) {
                    progress.onFailed("Login fallido")
                    return
                }
            }

            // Descarga de partes
            val partFiles = mutableListOf<File>()
            var globalDownloaded = 0L
            val globalTotal = params.fileSize

            for ((idx, fileId) in params.fileIds.withIndex()) {
                if (isCancelled()) { progress.onFailed("Cancelado"); cleanupTemp(); return }
                val partFile = File(tempDir, "part_${idx}_$fileId.tmp")

                // Reanudación: si ya existe con tamaño > 0, lo aceptamos (no podemos verificar tamaño exacto sin Content-Length)
                if (partFile.exists() && partFile.length() > 0) {
                    globalDownloaded += partFile.length()
                    progress.onProgress(globalDownloaded, globalTotal)
                    partFiles.add(partFile)
                    progress.onStatus("Parte ${idx + 1}/${params.fileIds.size} ya descargada")
                    continue
                }

                progress.onStatus("Descargando parte ${idx + 1}/${params.fileIds.size}…")
                val (ok, written) = downloadPart(
                    client, fileId, partFile,
                    globalDownloaded, globalTotal, progress
                )
                if (!ok) {
                    progress.onFailed("No se pudo descargar la parte ${idx + 1}")
                    return
                }
                globalDownloaded += written
                partFiles.add(partFile)
            }

            if (isCancelled()) { progress.onFailed("Cancelado"); cleanupTemp(); return }

            // Reensamblar + decodificar
            progress.onStatus(context.getString(R.string.status_assembling))
            val finalBytes = assembleAndDecode(partFiles)

            // Guardar en Downloads/BitZero
            val (uri, path) = saveToDownloads(finalBytes, params.filename)
            cleanupTemp()
            progress.onCompleted(uri, path)
        } catch (e: Exception) {
            progress.onFailed(e.message ?: "Error desconocido")
        }
    }

    private fun downloadPart(
        client: OkHttpClient,
        fileId: String,
        outputFile: File,
        globalDownloaded: Long,
        globalTotal: Long,
        progress: Progress
    ): Pair<Boolean, Long> {
        val urls = BitZeroUrl.generateDownloadUrls(params, fileId)

        for ((idx, url) in urls.withIndex()) {
            repeat(HttpClient.MAX_RETRIES) { attempt ->
                if (isCancelled()) return false to 0L
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", HttpClient.USER_AGENT)
                        .get()
                        .build()

                    client.newCall(req).execute().use { resp ->
                        if (resp.code == 200) {
                            val body = resp.body ?: return@use
                            var downloadedPart = 0L
                            FileOutputStream(outputFile).use { out ->
                                body.byteStream().use { input ->
                                    val buf = ByteArray(8192)
                                    while (true) {
                                        if (isCancelled()) return false to downloadedPart
                                        val n = input.read(buf)
                                        if (n <= 0) break
                                        out.write(buf, 0, n)
                                        downloadedPart += n
                                        progress.onProgress(globalDownloaded + downloadedPart, globalTotal)
                                    }
                                }
                            }
                            return true to downloadedPart
                        } else if (resp.code in 400..499) {
                            // 404 → probar siguiente patrón sin reintentar
                            return@repeat
                        }
                    }
                } catch (e: Exception) {
                    if (attempt < HttpClient.MAX_RETRIES - 1) Thread.sleep(2000)
                }
            }
        }
        return false to 0L
    }

    private fun assembleAndDecode(partFiles: List<File>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (part in partFiles) {
            val decoded = when (params.bitzeroMode) {
                1 -> Decoders.decodePng(part)
                2 -> Decoders.decodeHtml(part, null, params)
                3 -> Decoders.decodeZip(part, null)
                else -> part.readBytes()
            }
            out.write(decoded)
        }
        return out.toByteArray()
    }

    private fun saveToDownloads(data: ByteArray, fileName: String): Pair<Uri, String> {
        // Resolver colisiones de nombre
        val resolver = context.contentResolver
        val safeName = fileName.replace(Regex("[<>:\"/\\\\|?*]"), "_")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, guessMimeType(safeName))
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/BitZero")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: throw RuntimeException("No se pudo crear archivo")
            resolver.openOutputStream(uri)?.use { it.write(data) } ?: throw RuntimeException("Output null")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri to "Download/BitZero/$safeName"
        } else {
            // Fallback raro (minSdk=29 incluye Q, pero por si acaso)
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BitZero")
            if (!dir.exists()) dir.mkdirs()
            val out = File(dir, safeName)
            FileOutputStream(out).use { it.write(data) }
            Uri.fromFile(out) to out.absolutePath
        }
    }

    private fun guessMimeType(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".zip") -> "application/zip"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".doc") -> "application/msword"
            lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            lower.endsWith(".txt") -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun cleanupTemp() {
        try { tempDir.deleteRecursively() } catch (_: Exception) {}
    }

    fun cleanupOnly() = cleanupTemp()
}
