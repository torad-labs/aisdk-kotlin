package ai.torad.aisdk

public data class DataUrl(
    val mediaType: String,
    val base64: String,
) {
    public companion object {
        public fun parse(dataUrl: String): DataUrl {
            require(dataUrl.startsWith("data:")) { "Not a data URL" }
            val comma = dataUrl.indexOf(',')
            require(comma >= 0) { "Data URL is missing comma separator" }
            val metadata = dataUrl.substring(5, comma)
            val base64 = dataUrl.substring(comma + 1)
            require(metadata.endsWith(";base64")) { "Only base64 data URLs are supported" }
            return DataUrl(
                mediaType = metadata.removeSuffix(";base64").ifEmpty { "text/plain" },
                base64 = base64,
            )
        }
    }
}
