package com.aikayun.shop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class AuthorizedBrand(val name: String, val faviconUrl: String)
data class AuthorityDecision(
    val allowed: Boolean,
    val canonicalOrigin: String?,
    val siteType: String?,
    val brand: AuthorizedBrand?,
    val reason: String? = null
)

sealed interface AuthorityResult {
    data class Decision(val value: AuthorityDecision) : AuthorityResult
    data class Unavailable(val message: String) : AuthorityResult
}

class AppShellAuthorityClient(private val authorityOrigin: String) {
    suspend fun resolve(origin: String): AuthorityResult = withContext(Dispatchers.IO) {
        val connection = try {
            val encoded = URLEncoder.encode(origin, Charsets.UTF_8.name())
            URL("${authorityOrigin.trimEnd('/')}/api/app-shell/resolve?origin=$encoded")
                .openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return@withContext AuthorityResult.Unavailable("授权服务地址无效")
        }
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status !in 200..299) return@withContext AuthorityResult.Unavailable("授权服务暂时无法连接（$status）")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (output.length + count > 64 * 1024) return@withContext AuthorityResult.Unavailable("授权服务响应无效")
                    output.append(buffer, 0, count)
                }
                output.toString()
            }
            val envelope = JSONObject(body)
            if (envelope.optInt("code", -1) != 0) return@withContext AuthorityResult.Unavailable("授权服务暂时无法连接")
            val data = envelope.optJSONObject("data") ?: return@withContext AuthorityResult.Unavailable("授权服务响应无效")
            val returnedOrigin = data.optString("canonical_origin").takeIf { it.isNotBlank() }
            if (data.optInt("schema_version", -1) != 1 || returnedOrigin != origin) {
                return@withContext AuthorityResult.Unavailable("授权服务响应版本不匹配")
            }
            val allowed = data.optBoolean("allowed", false)
            val type = data.optString("site_type").takeIf { it == "myshop" || it == "external" }
            val brandData = data.optJSONObject("brand")
            val brand = brandData?.let {
                AuthorizedBrand(it.optString("site_name"), it.optString("favicon_url"))
            }
            AuthorityResult.Decision(
                AuthorityDecision(
                    allowed = allowed && type != null,
                    canonicalOrigin = returnedOrigin,
                    siteType = type,
                    brand = brand,
                    reason = data.optString("reason").takeIf { it.isNotBlank() }
                )
            )
        } catch (_: Exception) {
            AuthorityResult.Unavailable("授权服务暂时无法连接")
        } finally {
            connection.disconnect()
        }
    }
}
