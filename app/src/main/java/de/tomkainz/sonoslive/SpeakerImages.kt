package de.tomkainz.sonoslive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * The picture of a speaker's model: a photo from assets/speakers if the build has one,
 * otherwise the small picture a Sonos or UPnP speaker serves itself. Null means neither
 * is there, for example an unknown model that cannot be reached right now, and always
 * for Cast and AirPlay speakers, which offer no picture.
 */
object SpeakerImages {

    private const val MAX_ICON_BYTES = 1024 * 1024

    /** Models that look the same as another one. */
    private val SAME_LOOK = mapOf(
        "arc-sl" to "arc",
        "era-100-pro" to "era-100",
        "zp90" to "connect",
        "zp120" to "connect-amp"
    )

    private val loaded = ConcurrentHashMap<String, ImageBitmap>()

    /** "Sonos Play:1" becomes "play-1", the name of the photo. */
    fun key(model: String): String {
        val key = model.lowercase().removePrefix("sonos").replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return SAME_LOOK[key] ?: key
    }

    fun cached(model: String): ImageBitmap? = loaded[key(model)]

    suspend fun load(context: Context, speaker: Speaker): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = key(speaker.model)
        if (key.isEmpty()) return@withContext null
        loaded[key]?.let { return@withContext it }
        // The photos are of Sonos models, and another brand's "One" is not one of them.
        val photo = if (speaker.protocol != SpeakerProtocol.SONOS) null else runCatching {
            context.assets.open("speakers/$key.webp").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        val bitmap = photo ?: runCatching { fromSpeaker(context, speaker, key) }.getOrNull()
        AppState.log("Speaker picture for ${speaker.model}: " + when {
            photo != null -> "photo"
            bitmap != null -> "from the speaker"
            else -> "none, drawn"
        })
        bitmap?.asImageBitmap()?.also { loaded[key] = it }
    }

    /** Kept on disk, so the picture is there even while the speaker is off. */
    private fun fromSpeaker(context: Context, speaker: Speaker, key: String): Bitmap? {
        val file = File(context.filesDir, "speaker-icons/$key.png")
        if (file.exists()) return BitmapFactory.decodeFile(file.path)
        val description = when (speaker.protocol) {
            SpeakerProtocol.SONOS -> "http://${speaker.ip}:1400/xml/device_description.xml"
            SpeakerProtocol.UPNP -> speaker.address
            else -> return null
        }
        val xml = fetch(description).decodeToString()
        val path = Regex("<icon>.*?<url>(.*?)</url>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            ?: return null
        val bytes = fetch(URL(URL(description), path).toString())
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return bitmap
    }

    private fun fetch(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
        }
        try {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            connection.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    check(out.size() <= MAX_ICON_BYTES) { "Too big: $url" }
                }
            }
            return out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }
}
