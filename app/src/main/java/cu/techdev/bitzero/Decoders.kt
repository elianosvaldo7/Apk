package cu.techdev.bitzero

import android.util.Base64
import org.jsoup.Jsoup
import java.io.File
import java.util.zip.ZipFile

/**
 * Decodificadores BitZero — replican decode_png(), decode_html() y decode_zip() de bitzero.py.
 */
object Decoders {

    private val PNG_HEADER = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53,
        0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
        0x54, 0x78, 0x9C.toByte(), 0x63, 0x60, 0x60, 0x60, 0x00,
        0x00, 0x00, 0x04, 0x00, 0x01, 0xF6.toByte(), 0x17, 0x38,
        0x55, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
        0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
    )

    /** Modo 1 — quita la cabecera PNG. */
    fun decodePng(file: File): ByteArray {
        val data = file.readBytes()
        return if (data.size >= PNG_HEADER.size && data.copyOfRange(0, PNG_HEADER.size).contentEquals(PNG_HEADER)) {
            data.copyOfRange(PNG_HEADER.size, data.size)
        } else data
    }

    /** Modo 2 — extrae base64 del HTML, aplica XOR inverso. */
    fun decodeHtml(file: File, manualXorKey: String?, params: BitZeroParams): ByteArray {
        val content = file.readText(Charsets.UTF_8)

        var encoded: String? = null
        val doc = Jsoup.parse(content)

        doc.getElementById("encoded-data")?.text()?.trim()?.let { if (it.isNotEmpty()) encoded = it }
        if (encoded == null) {
            doc.getElementById("data")?.text()?.trim()?.let { if (it.isNotEmpty()) encoded = it }
        }
        if (encoded == null && "<bytes>" in content && "</bytes>" in content) {
            encoded = content.substringAfter("<bytes>").substringBefore("</bytes>")
        }
        if (encoded == null) {
            val regex = Regex("[A-Za-z0-9+/=]{100,}")
            encoded = regex.findAll(content).maxByOrNull { it.value.length }?.value
        }

        val encodedFinal = encoded ?: throw RuntimeException("No se encontraron datos codificados")

        // Lista de posibles claves
        val possibleKeys = mutableListOf<String>()
        manualXorKey?.let { possibleKeys.add(it) }
        params.encryptionKey?.let { possibleKeys.add(it) }
        possibleKeys.addAll(listOf("default_key_1", "default_key_2", "default_key_3", ""))
        val uniqueKeys = possibleKeys.distinct()

        for (key in uniqueKeys) {
            try {
                val decoded = Base64.decode(encodedFinal, Base64.DEFAULT)
                if (key.isEmpty()) {
                    return decoded
                }
                val keyBytes = key.toByteArray(Charsets.UTF_8)
                val restored = ByteArray(decoded.size)
                for (i in decoded.indices) {
                    restored[i] = (decoded[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
                }
                try {
                    return Base64.decode(restored, Base64.DEFAULT)
                } catch (e: Exception) {
                    continue
                }
            } catch (e: Exception) {
                continue
            }
        }
        throw RuntimeException("No se pudo decodificar con ninguna clave")
    }

    /** Modo 3 — descomprime el ZIP (primer archivo). */
    fun decodeZip(file: File, password: String?): ByteArray {
        ZipFile(file).use { zf ->
            val entry = zf.entries().toList().firstOrNull() ?: throw RuntimeException("ZIP vacío")
            // Nota: java.util.zip no soporta contraseñas. Si se requiere ZIP cifrado, habría que añadir zip4j.
            // Para mantener la app ligera, asumimos ZIP sin contraseña (modo 3 raramente usado).
            return zf.getInputStream(entry).readBytes()
        }
    }
}
