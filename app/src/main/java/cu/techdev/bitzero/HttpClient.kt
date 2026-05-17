package cu.techdev.bitzero

import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Cliente HTTP equivalente al requests.Session() de bitzero.py.
 * - Acepta certificados SSL inválidos (igual que verify=False en Python)
 * - Cookies persistentes para mantener la sesión OJS tras el login
 * - User-Agent realista
 * - Reintentos automáticos vía OkHttp retryOnConnectionFailure
 */
object HttpClient {

    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    const val DEFAULT_TIMEOUT_SEC = 45L
    const val MAX_RETRIES = 3

    fun newSession(timeoutSec: Long = DEFAULT_TIMEOUT_SEC): OkHttpClient {
        val cookieManager = CookieManager().apply { setCookiePolicy(CookiePolicy.ACCEPT_ALL) }

        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
        })
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, trustAll, java.security.SecureRandom()) }

        return OkHttpClient.Builder()
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS) // ilimitado para descargas grandes
            .retryOnConnectionFailure(true)
            .cookieJar(JavaNetCookieJar(cookieManager))
            .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .build()
    }
}
