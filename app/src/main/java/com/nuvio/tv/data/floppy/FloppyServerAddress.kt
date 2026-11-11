package com.nuvio.tv.data.floppy

import java.net.Inet4Address
import java.net.InetAddress
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A validated Floppy server location: an origin plus an optional path prefix, without a trailing slash.
 *
 * Plain http is accepted only for hosts that are not reachable from the public internet, so a typo can
 * never send the sign-in over an untrusted network.
 */
class FloppyServerAddress private constructor(
    val scheme: String,
    val host: String,
    val port: Int,
    val pathPrefix: String
) {
    /** `scheme://host[:port]`, no path. Compared to decide where a bearer token may be sent. */
    val origin: String
        get() = buildString {
            append(scheme).append("://")
            append(if (':' in host) "[$host]" else host)
            if (port != defaultPort(scheme)) append(':').append(port)
        }

    /** Origin plus path prefix, never ending in a slash. */
    val baseUrl: String
        get() = origin + pathPrefix

    /** Absolute URL for an API path such as `/api/v1/info/`. */
    fun url(path: String): String {
        require(path.startsWith('/') && !path.startsWith("//"))
        return baseUrl + path
    }

    fun hasSameOrigin(url: HttpUrl): Boolean =
        url.scheme == scheme && url.host == host && url.port == port

    override fun equals(other: Any?): Boolean = other is FloppyServerAddress && other.baseUrl == baseUrl
    override fun hashCode(): Int = baseUrl.hashCode()
    override fun toString(): String = "FloppyServerAddress($baseUrl)"

    companion object {
        /** Returns null when [input] is not an acceptable server address. */
        fun parse(input: String): FloppyServerAddress? {
            val trimmed = input.trim()
            if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return null
            val parsed = (if ("://" in trimmed) trimmed else "https://$trimmed").toHttpUrlOrNull() ?: return null
            // A bare "192.168.1.5:8000" has no scheme; local hosts get http, everything else https.
            val url = if ("://" in trimmed || !isPrivateHost(parsed.host)) parsed else
                "http://$trimmed".toHttpUrlOrNull() ?: return null
            if (url.scheme != "http" && url.scheme != "https") return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            if (url.query != null || url.fragment != null || '?' in trimmed || '#' in trimmed) return null
            if (url.scheme == "http" && !isPrivateHost(url.host)) return null
            return FloppyServerAddress(url.scheme, url.host, url.port, url.encodedPath.trimEnd('/'))
        }

        private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

        /** Loopback, RFC1918, CGNAT, link-local, IPv6 ULA/link-local and `.local` hosts. */
        fun isPrivateHost(host: String): Boolean {
            val name = host.lowercase()
            if (name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local")) return true
            if (':' in name) return isPrivateIpv6(name)
            val octets = ipv4Octets(name) ?: return false
            return isPrivateIpv4(octets)
        }

        private fun ipv4Octets(host: String): IntArray? {
            val parts = host.split('.')
            if (parts.size != 4) return null
            val octets = IntArray(4)
            for ((index, part) in parts.withIndex()) {
                if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
                octets[index] = part.toInt().takeIf { it in 0..255 } ?: return null
            }
            return octets
        }

        private fun isPrivateIpv4(o: IntArray): Boolean =
            o[0] == 127 ||
                o[0] == 10 ||
                (o[0] == 172 && o[1] in 16..31) ||
                (o[0] == 192 && o[1] == 168) ||
                (o[0] == 100 && o[1] in 64..127) ||
                (o[0] == 169 && o[1] == 254)

        private fun isPrivateIpv6(host: String): Boolean {
            // Only IPv6 literals reach here (they contain ':'), so no DNS lookup can happen.
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            if (address is Inet4Address) {
                return isPrivateIpv4(address.address.map { it.toInt() and 0xFF }.toIntArray())
            }
            val b = address.address
            if (b.size != 16) return false
            val first = b[0].toInt() and 0xFF
            val second = b[1].toInt() and 0xFF
            val loopback = b.take(15).all { it.toInt() == 0 } && b[15].toInt() == 1
            return loopback ||
                (first and 0xFE) == 0xFC ||
                (first == 0xFE && (second and 0xC0) == 0x80)
        }
    }
}
