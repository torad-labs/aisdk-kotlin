package ai.torad.aisdk

internal object MediaTypes {

    fun toExtension(mediaType: String): String {
        val subtype = mediaType.lowercase().substringAfter('/', missingDelimiterValue = "")
        return when (subtype) {
            "mpeg" -> "mp3"
            "x-wav" -> "wav"
            "opus" -> "ogg"
            "mp4" -> "m4a"
            "x-m4a" -> "m4a"
            else -> subtype
        }
    }

    fun stripFileExtension(filename: String): String = filename.substringBefore('.', filename)

    fun detect(filename: String? = null, dataUrl: String? = null, explicit: String? = null): String? {
        explicit?.let { return it }
        dataUrl?.takeIf { it.startsWith("data:") }?.let { return DataUrl.parse(it).mediaType }
        val ext = filename?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
        return when (ext) {
            "txt" -> "text/plain"
            "json" -> "application/json"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            else -> null
        }
    }
}
