package com.aikayun.shop

import android.icu.text.IDNA
import java.net.URI
import java.util.Locale

data class CanonicalOrigin(val host: String, val value: String)

object NativeOrigin {
    private val uts46 = IDNA.getUTS46Instance(
        IDNA.USE_STD3_RULES or IDNA.CHECK_BIDI or IDNA.CHECK_CONTEXTJ or IDNA.NONTRANSITIONAL_TO_ASCII
    )

    /** Accepts an HTTPS URL or bare domain, then returns its origin; deep paths are discarded. */
    fun fromUserInput(raw: String): CanonicalOrigin? {
        val input = raw.trim()
        if (input.isEmpty() || input.length > 2048 || input.any { it.isWhitespace() || it.isISOControl() } || '\\' in input) return null
        val hasScheme = input.contains("://")
        if (hasScheme && !input.substringBefore("://").equals("https", ignoreCase = true)) return null
        val url = if (hasScheme) input else "https://$input"
        return parse(url)
    }

    fun parse(raw: String): CanonicalOrigin? {
        if (raw.isBlank() || raw.length > 2048 || raw.any { it.isISOControl() || it.isWhitespace() } || '\\' in raw) return null
        return try {
            val uri = URI(raw)
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawAuthority.isNullOrBlank()
                || uri.rawUserInfo != null || uri.rawAuthority.contains('@') || uri.rawAuthority.contains('%')
                || uri.rawAuthority.contains('/') || uri.rawAuthority.contains('[') || uri.rawAuthority.contains(']')) return null
            val authority = uri.rawAuthority
            val colon = authority.lastIndexOf(':')
            val hostInput = if (colon >= 0) {
                if (authority.indexOf(':') != colon || authority.substring(colon + 1) != "443") return null
                authority.substring(0, colon)
            } else authority
            if (hostInput.isEmpty() || hostInput.endsWith("..")) return null
            val withoutDot = hostInput.removeSuffix(".")
            val info = IDNA.Info()
            val ascii = StringBuilder()
            uts46.nameToASCII(withoutDot, ascii, info)
            if (info.hasErrors()) return null
            val host = ascii.toString().lowercase(Locale.ROOT)
            if (!validHost(host)) return null
            CanonicalOrigin(host, "https://$host")
        } catch (_: Exception) {
            null
        }
    }

    fun isSameOrigin(first: String, second: String): Boolean {
        val a = parse(first) ?: return false
        val b = parse(second) ?: return false
        return a.host == b.host && a.value == b.value
    }

    private fun validHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253 || host == "localhost" || host.endsWith(".localhost")) return false
        val labels = host.split('.')
        if (labels.size < 2) return false
        if (labels.any { it.isEmpty() || it.length > 63 || !it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) }) return false
        return !labels.all { it.matches(Regex("(?i)(?:[0-9]+|0x[0-9a-f]+|0[0-7]+)")) }
    }
}
