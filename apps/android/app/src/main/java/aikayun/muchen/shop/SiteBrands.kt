package aikayun.muchen.shop

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import com.caverock.androidsvg.SVG
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

/** Branding is appearance only; callers must check current authorization/view identity before applying. */
class SiteBrands(private val preferences: SharedPreferences) {
    fun cached(origin: String): Pair<String?, Bitmap?> {
        val name = preferences.getString("name:$origin", null)
        val icon = runCatching { preferences.getString("icon:$origin", null)?.let { Base64.decode(it, Base64.DEFAULT) }?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
        return name to icon
    }
    fun save(origin: String, name: String, icon: Bitmap?) {
        val encoded = icon?.let { bitmap -> ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) } }
        preferences.edit().putString("name:$origin", name).putString("icon:$origin", encoded).apply()
    }
    fun icon(origin: String, path: String): Bitmap? = runCatching {
        var url = URI(origin).resolve(path).toURL()
        require(url.protocol == "https" && url.userInfo == null)
        var bytes: ByteArray? = null
        var contentType = ""
        for (hop in 0..3) {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5000; connection.readTimeout = 5000
                connection.instanceFollowRedirects = false
                if (connection.responseCode in 300..399) {
                    require(hop < 3)
                    url = url.toURI().resolve(connection.getHeaderField("Location")).toURL()
                    require(url.protocol == "https" && url.userInfo == null)
                    continue
                }
                require(connection.responseCode == 200 && connection.contentLengthLong <= 524288)
                contentType = connection.contentType ?: ""
                bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (output.size() <= 524288) {
                        val n = input.read(buffer, 0, minOf(buffer.size, 524289 - output.size()))
                        if (n < 0) break
                        output.write(buffer,0,n)
                    }
                    output.toByteArray().also { require(it.size <= 524288) }
                }
                break
            } finally { connection.disconnect() }
        }
        val data = requireNotNull(bytes)
        if (contentType.contains("svg") || data.take(256).toByteArray().toString(Charsets.UTF_8).contains("<svg")) {
            val svg = SVG.getFromInputStream(data.inputStream())
            // AndroidSVG parses and renders vectors; external files/fonts/images are not registered.
            svg.documentWidth = 96f; svg.documentHeight = 96f
            Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it)) }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data,0,data.size,bounds)
            require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384)
            val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth,bounds.outHeight) / 128) }
            requireNotNull(BitmapFactory.decodeByteArray(data,0,data.size,options)).let { Bitmap.createScaledBitmap(it,96,96,true) }
        }
    }.getOrNull()
}
