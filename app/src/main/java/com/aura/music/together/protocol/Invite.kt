package com.aura.music.together.protocol

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * Everything a guest needs to join, carried in the link's fragment (after `#`). Browsers never
 * send fragments to servers, so the site that hosts the link learns nothing: not the
 * address, not the key, not the names.
 *
 * A guest tries the local address first ([host], when the host had one) and falls back to the
 * internet relay room named after [sessionId] when [relay] is set.
 */
data class Invite(
    val sessionId: String,
    val host: String?,
    val port: Int,
    val key: ByteArray,
    val hostName: String,
    val sessionName: String,
    val relay: Boolean = false,
) {
    fun toLink(baseUrl: String): String {
        val fragment = listOfNotNull(
            "v" to "1",
            "s" to sessionId,
            host?.let { "h" to "$it:$port" },
            ("r" to "1").takeIf { relay },
            "k" to Base64.getUrlEncoder().withoutPadding().encodeToString(key),
            "n" to hostName,
            "j" to sessionName,
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8").replace("+", "%20")}" }
        return baseUrl.trimEnd('/') + "/#" + fragment
    }

    override fun equals(other: Any?) = other is Invite && sessionId == other.sessionId && host == other.host &&
        port == other.port && key.contentEquals(other.key) && hostName == other.hostName && sessionName == other.sessionName &&
        relay == other.relay

    override fun hashCode() = sessionId.hashCode()

    companion object {
        private val sessionIdPattern = Regex("^[A-Za-z0-9_-]{6,64}$")
        private val hostPattern = Regex("^[0-9A-Za-z.:-]{1,64}$")

        /** Parses a link produced by [toLink]; returns null for anything malformed. */
        fun parse(link: String): Invite? {
            val fragment = link.substringAfter('#', "").ifEmpty { return null }
            val params = fragment.split('&').mapNotNull { part ->
                val (k, v) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                k to runCatching { URLDecoder.decode(v, "UTF-8") }.getOrNull()
            }.toMap()
            if (params["v"] != "1") return null
            val sessionId = params["s"]?.takeIf(sessionIdPattern::matches) ?: return null
            val relay = params["r"] == "1"
            val address = params["h"]
            var host: String? = null
            var port = 0
            if (address != null) {
                host = address.substringBeforeLast(':').takeIf(hostPattern::matches) ?: return null
                port = address.substringAfterLast(':').toIntOrNull()?.takeIf { it in 1024..65535 } ?: return null
            } else if (!relay) {
                return null
            }
            val key = runCatching { Base64.getUrlDecoder().decode(params["k"]) }.getOrNull()?.takeIf { it.size == SecureChannel.KEY_BYTES } ?: return null
            return Invite(
                sessionId = sessionId,
                host = host,
                port = port,
                key = key,
                hostName = params["n"].orEmpty().take(40).ifBlank { "AURA" },
                sessionName = params["j"].orEmpty().take(60),
                relay = relay,
            )
        }
    }
}
