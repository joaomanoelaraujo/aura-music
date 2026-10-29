package com.aura.music.link

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * What the computer's QR code carries: where to connect on the local network and the one-time
 * pairing secret. It is shown on screen only, never sent to a server or put in a web link.
 *
 * Format: `aura-link://pair?v=1&n=<name>&h=<ip1>,<ip2>&p=<port>&s=<base64url secret>`
 */
data class PairingPayload(val name: String, val hosts: List<String>, val port: Int, val secret: ByteArray) {

    fun encode(): String = buildString {
        append(PREFIX).append("v=").append(LinkProtocol.VERSION)
        append("&n=").append(URLEncoder.encode(name, "UTF-8").replace("+", "%20"))
        append("&h=").append(hosts.joinToString(","))
        append("&p=").append(port)
        append("&s=").append(Base64.getUrlEncoder().withoutPadding().encodeToString(secret))
    }

    override fun equals(other: Any?) = other is PairingPayload && name == other.name && hosts == other.hosts &&
        port == other.port && secret.contentEquals(other.secret)

    override fun hashCode() = 31 * hosts.hashCode() + port

    companion object {
        private const val PREFIX = "aura-link://pair?"
        private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

        /** Parses a scanned code; null for anything that isn't a valid AURA Link pairing code. */
        fun parse(text: String): PairingPayload? {
            if (!text.startsWith(PREFIX)) return null
            val params = text.removePrefix(PREFIX).split('&').mapNotNull { part ->
                val (k, v) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                k to runCatching { URLDecoder.decode(v, "UTF-8") }.getOrNull()
            }.toMap()
            if (params["v"] != LinkProtocol.VERSION.toString()) return null
            val hosts = params["h"].orEmpty().split(',').map(String::trim).filter { h ->
                ipv4.matchEntire(h)?.groupValues?.drop(1)?.all { (it.toIntOrNull() ?: 999) <= 255 } == true
            }
            val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val secret = runCatching { Base64.getUrlDecoder().decode(params["s"]) }.getOrNull()?.takeIf { it.size == 32 } ?: return null
            if (hosts.isEmpty()) return null
            return PairingPayload(params["n"].orEmpty().take(40).ifBlank { "Computador" }, hosts.take(4), port, secret)
        }
    }
}
