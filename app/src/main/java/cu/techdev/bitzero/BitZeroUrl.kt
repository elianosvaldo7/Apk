package cu.techdev.bitzero

import android.util.Base64

/**
 * Parser de URL BitZero — replica exactamente la lógica de bitzero.py (v3.4).
 *
 * Formato de URL:
 *   https://bitzero.techdev.cu/{filesize}-{repo}/{file_ids}/{mode}/{key_encoded}/{name_b64}[/{hash}]
 *
 * Donde key_encoded es: host-user-pass-submissionId-contexto[-mode][-timestamp][-encKey]
 * Cada parte está codificada en base64 con '=' reemplazado por '#' y '==' por '@'.
 */
data class BitZeroParams(
    val host: String,
    val username: String,
    val password: String,
    val submissionId: String,
    val contexto: String,
    val fileIds: List<String>,
    val bitzeroMode: Int,
    val bitzeroModeFromKey: Int?,
    val timestamp: Long?,
    val encryptionKey: String?,
    val fileSize: Long,
    val filename: String,
    val repo: String,
    val verificationHash: String?,
    val originalUrl: String
)

object BitZeroUrl {

    private fun decodeKeyPart(raw: String): String {
        val fixed = raw.replace('#', '=').replace('@', '=').let {
            // legacy compatibility: bitzero.py replaces == with @ and = with #
            // but our encoder only replaces '=' with '#', so re-handle:
            it
        }
        val bytes = Base64.decode(fixed, Base64.DEFAULT)
        return String(bytes, Charsets.UTF_8)
    }

    @Throws(IllegalArgumentException::class)
    fun parse(rawUrl: String): BitZeroParams {
        val url = rawUrl.trim().removePrefix("bitzero ").trim()
        val parts = url.split("/")
        if (parts.size < 7) throw IllegalArgumentException("URL inválida o incompleta")

        // Negative indexing helper
        fun fromEnd(i: Int) = parts[parts.size - i]

        val filenameB64 = fromEnd(2)
        val keyEncoded = fromEnd(3)
        val bitzeroModeStr = fromEnd(4)
        val token = fromEnd(5)
        val sizeRepo = fromEnd(6)
        val verificationHash = if (parts.size > 7 && parts.last().length == 8) parts.last() else null

        if (!sizeRepo.contains('-')) throw IllegalArgumentException("Formato inválido en size-repo: $sizeRepo")
        val (fileSizeStr, repo) = sizeRepo.split('-', limit = 2)
        val fileSize = fileSizeStr.toLongOrNull() ?: throw IllegalArgumentException("Tamaño inválido: $fileSizeStr")

        val keyParts = keyEncoded.split('-')
        if (keyParts.size < 5) throw IllegalArgumentException("Clave de autenticación incompleta")

        val host = decodeKeyPart(keyParts[0])
        val username = decodeKeyPart(keyParts[1])
        val password = decodeKeyPart(keyParts[2])
        val submissionId = decodeKeyPart(keyParts[3])
        val contexto = decodeKeyPart(keyParts[4])

        val bitzeroModeFromKey: Int? = if (keyParts.size >= 6) decodeKeyPart(keyParts[5]).toIntOrNull() else null
        val timestamp: Long? = if (keyParts.size >= 7) decodeKeyPart(keyParts[6]).toLongOrNull() else null
        val encryptionKey: String? = if (keyParts.size >= 8) decodeKeyPart(keyParts[7]) else null

        val bitzeroMode = bitzeroModeStr.toIntOrNull() ?: (bitzeroModeFromKey ?: 0)

        val filename = try {
            String(Base64.decode(filenameB64.replace('_', '='), Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            filenameB64
        }

        val fileIds = token.split('-').filter { it.isNotEmpty() }

        return BitZeroParams(
            host = host,
            username = username,
            password = password,
            submissionId = submissionId,
            contexto = contexto,
            fileIds = fileIds,
            bitzeroMode = bitzeroMode,
            bitzeroModeFromKey = bitzeroModeFromKey,
            timestamp = timestamp,
            encryptionKey = encryptionKey,
            fileSize = fileSize,
            filename = filename,
            repo = repo,
            verificationHash = verificationHash,
            originalUrl = url
        )
    }

    /**
     * Genera los 6 patrones de URL de descarga (igual que bitzero.py).
     */
    fun generateDownloadUrls(params: BitZeroParams, fileId: String): List<String> {
        val base = params.host.trimEnd('/')
        val ctx = if (params.contexto.isNotEmpty()) "/index.php/${params.contexto}" else "/index.php"
        val sid = params.submissionId
        return listOf(
            "$base/\$\$\$call\$\$\$/api/file/file-api/download-file?submissionFileId=$fileId&submissionId=$sid&stageId=1",
            "$base$ctx/api/v1/files/$fileId/download?submissionId=$sid&stageId=1",
            "$base$ctx/api/v1/files/$fileId/download?submissionId=$sid",
            "$base$ctx/\$\$\$call\$\$\$/api/file/file-api/download-file?submissionFileId=$fileId&submissionId=$sid&stageId=1",
            "$base/api/v1/files/$fileId/download?submissionId=$sid&stageId=1",
            "$base$ctx/api/v1/submissions/$sid/files/$fileId/download"
        )
    }
}
