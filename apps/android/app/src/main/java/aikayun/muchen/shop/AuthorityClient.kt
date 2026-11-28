package aikayun.muchen.shop

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.IOException

data class SiteBrand(val name: String? = null, val iconUrl: String? = null)

sealed class Authorization {
    data class Allowed(val siteType: String = "myshop", val brand: SiteBrand? = null) : Authorization()
    data class Denied(val reason: String) : Authorization()
    data class Failed(val message: String) : Authorization()
}

object ResolveProtocol {
    fun parse(body: String, origin: String): Authorization {
        val root = JSONObject(body)
        require(root.opt("code") is Int && root.getInt("code") == 0) { "接口返回异常" }
        val data = root.getJSONObject("data")
        require(data.opt("schema_version") is Int && data.getInt("schema_version") == 1) { "不支持的授权协议" }
        require(data.opt("allowed") is Boolean) { "授权响应格式错误" }
        require(data.opt("canonical_origin") is String && data.getString("canonical_origin") == origin) { "授权网址不匹配" }
        val reason = data.opt("reason")
        require(reason == null || reason === JSONObject.NULL || reason is String) { "授权原因格式错误" }
        // Denials need no site_type or brand, even when the entire service is disabled.
        if (!data.getBoolean("allowed")) {
            require(reason is String && reason.isNotBlank()) { "缺少拒绝原因" }
            return Authorization.Denied(reason)
        }
        require(data.opt("site_type") is String && data.getString("site_type") in setOf("myshop", "external")) { "站点类型无效" }
        val brand = data.optJSONObject("brand")?.let { value ->
            fun optional(key: String, limit: Int) = (value.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= limit }
            SiteBrand(optional("site_name", 160), optional("favicon_url", 2048))
        }
        return Authorization.Allowed(data.getString("site_type"), brand)
    }
}

open class AuthorityClient {
    open fun resolve(origin: String): Authorization {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(BuildConfig.AUTHORITY_ORIGIN + "/api/app-shell/resolve?origin=" + URLEncoder.encode(origin, "UTF-8"))
                .openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != 200) return Authorization.Failed("授权服务请求失败（HTTP ${connection.responseCode}）")
            val bytes = connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= 65_536) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, 65_537 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > 65_536) return Authorization.Failed("授权响应过大")
            try { ResolveProtocol.parse(bytes.toString(Charsets.UTF_8), origin) }
            catch (_: Exception) { Authorization.Failed("授权响应无效，请重试") }
        } catch (_: IOException) {
            Authorization.Failed("无法连接授权服务，请检查网络后重试")
        } catch (_: Exception) {
            Authorization.Failed("授权请求失败，请重试")
        } finally { connection?.disconnect() }
    }
}
