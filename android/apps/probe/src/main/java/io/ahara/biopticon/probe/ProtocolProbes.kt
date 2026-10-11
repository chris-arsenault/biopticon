package io.ahara.biopticon.probe

import java.net.HttpURLConnection
import java.net.URI

/** Owner-selected interface discovery. Never sends credentials or follows redirects. */
class ProtocolProbes(private val log: EventLog) {
    fun inspect(target: String, endpoint: String) {
        val uri = URI(endpoint)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null) { "HTTPS_ENDPOINT_REQUIRED" }
        val run = log.begin()
        try {
            for (method in listOf("GET", "HEAD", "OPTIONS")) {
                val started = System.nanoTime()
                try {
                    val connection = uri.toURL().openConnection() as HttpURLConnection
                    try {
                        connection.requestMethod = method; connection.instanceFollowRedirects = false
                        connection.connectTimeout = 10000; connection.readTimeout = 10000
                        val status = connection.responseCode
                        val challenge = connection.getHeaderField("WWW-Authenticate")?.substringBefore(' ')?.take(32)
                        val allowed = connection.getHeaderField("Allow")?.split(',')?.map { it.trim() }?.filter { it in setOf("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS", "PATCH") }.orEmpty()
                        log.emit(run, "GI-04", "unauthenticated.$method", target, "protocol_request", "PASS", "HTTP_RESPONSE_OBSERVED", listOf(
                            Finding("protocol", "method", text = method), Finding("protocol", "http_status", count = status.toLong()),
                            Finding("protocol", "host", text = uri.host)) +
                            listOfNotNull(challenge?.let { Finding("protocol", "auth_challenge", text = it) }) + allowed.map { Finding("protocol", "allowed_method", text = it) },
                            (System.nanoTime() - started) / 1000000)
                    } finally { connection.disconnect() }
                } catch (error: Exception) {
                    log.emit(run, "GI-04", "unauthenticated.$method", target, "protocol_request", "FAIL", "REQUEST_FAILED", listOf(Finding("error", "class", text = error.javaClass.simpleName)))
                }
            }
            log.finish(run, "FINISHED")
        } catch (error: Exception) { log.finish(run, "INTERRUPTED"); throw error }
    }
}
