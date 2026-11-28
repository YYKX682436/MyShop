package aikayun.muchen.shop

import android.icu.text.IDNA
import java.net.URI
import java.util.Locale

/** One HTTPS origin, independent of the central service's domain rules. */
object OriginPolicy {
    fun normalize(input: String): String? {
        val text = input.trim()
        if (text.isEmpty() || text.length > 2048 || text.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
        return try {
            val uri = URI(if (text.contains("://")) text else "https://$text")
            if (!uri.scheme.equals("https", true) || uri.rawUserInfo != null) return null
            val authority = uri.rawAuthority ?: return null
            if (authority.any { it in "@%[]\\" }) return null
            val rawHost = if (':' in authority) {
                if (authority.count { it == ':' } != 1 || authority.substringAfter(':') != "443") return null
                authority.substringBefore(':')
            } else authority
            val info = IDNA.Info()
            val result = StringBuilder()
            IDNA.getUTS46Instance(IDNA.USE_STD3_RULES or IDNA.CHECK_BIDI or IDNA.CHECK_CONTEXTJ or IDNA.NONTRANSITIONAL_TO_ASCII)
                .nameToASCII(rawHost, result, info)
            if (info.hasErrors()) return null
            val host = result.toString().lowercase(Locale.ROOT).removeSuffix(".")
            val labels = host.split('.')
            if (host.length > 253 || labels.size < 2 || host == "localhost" || host.endsWith(".localhost")) return null
            if (labels.any { it.length !in 1..63 || !it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) }) return null
            if (labels.all { it.matches(Regex("(?:[0-9]+|0x[0-9a-f]+)")) }) return null
            "https://$host/"
        } catch (_: Exception) { null }
    }
}
