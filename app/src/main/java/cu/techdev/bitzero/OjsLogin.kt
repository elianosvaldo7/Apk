package cu.techdev.bitzero

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Login OJS — equivalente a ojs_login() de bitzero.py.
 */
object OjsLogin {

    /**
     * @return CSRF token si el login fue exitoso, null en caso contrario.
     */
    fun login(
        client: OkHttpClient,
        host: String,
        username: String,
        password: String,
        contexto: String
    ): String? {
        val base = host.trimEnd('/')
        val loginUrl = if (contexto.isNotEmpty()) "$base/index.php/$contexto/login" else "$base/index.php/login"
        val signInUrl = if (contexto.isNotEmpty()) "$base/index.php/$contexto/login/signIn" else "$base/index.php/login/signIn"

        repeat(HttpClient.MAX_RETRIES) { attempt ->
            try {
                // 1. GET de la página de login para extraer csrfToken
                val getReq = Request.Builder()
                    .url(loginUrl)
                    .header("User-Agent", HttpClient.USER_AGENT)
                    .get()
                    .build()

                val csrfToken = client.newCall(getReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    val doc = Jsoup.parse(body)
                    val input = doc.select("input[name=csrfToken]").first()
                    input?.attr("value")
                } ?: throw RuntimeException("No se pudo obtener el token CSRF")

                if (csrfToken.isEmpty()) throw RuntimeException("Token CSRF vacío")

                // 2. POST de credenciales
                val form = FormBody.Builder()
                    .add("csrfToken", csrfToken)
                    .add("username", username)
                    .add("password", password)
                    .add("remember", "1")
                    .add("source", "")
                    .build()

                val postReq = Request.Builder()
                    .url(signInUrl)
                    .header("User-Agent", HttpClient.USER_AGENT)
                    .post(form)
                    .build()

                client.newCall(postReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if ("Cerrar sesión" in body || "Logout" in body || "submissionId=" in body || "Sign out" in body) {
                        return csrfToken
                    }
                }
            } catch (e: Exception) {
                if (attempt == HttpClient.MAX_RETRIES - 1) throw e
                Thread.sleep(2000)
            }
        }
        return null
    }

    /**
     * Comprueba si el servidor responde antes de intentar login.
     */
    fun testConnection(client: OkHttpClient, host: String): Boolean {
        return try {
            val req = Request.Builder()
                .url(host.trimEnd('/'))
                .header("User-Agent", HttpClient.USER_AGENT)
                .head()
                .build()
            client.newCall(req).execute().use { it.code < 500 }
        } catch (e: Exception) {
            try {
                val req = Request.Builder()
                    .url(host.trimEnd('/'))
                    .header("User-Agent", HttpClient.USER_AGENT)
                    .get()
                    .build()
                client.newCall(req).execute().use { it.code < 500 }
            } catch (e2: Exception) {
                false
            }
        }
    }
}
