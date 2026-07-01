@file:kotlin.jvm.JvmName("MediaModelsKt")
@file:kotlin.jvm.JvmMultifileClass

package ai.torad.aisdk

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.jvm.JvmOverloads

@Poko
/** @since 0.3.0-beta01 */
public class GeneratedFile(
    /** @since 0.3.0-beta01 */
    public val mediaType: String,
    /** @since 0.3.0-beta01 */
    public val base64: String,
    /** @since 0.3.0-beta01 */
    public val filename: String? = null,
    /** @since 0.3.0-beta01 */
    public val providerMetadata: ProviderMetadata = ProviderMetadata.None,
    /** @since 0.3.0-beta01 */
    public val url: String? = null,
)

/** @since 0.3.0-beta01 */
public sealed class FileData {
    /** @since 0.3.0-beta01 */
    public abstract val mediaType: String?
    /** @since 0.3.0-beta01 */
    public abstract val filename: String?

    @Poko
    /** @since 0.3.0-beta01 */
    public class Base64(
        /** @since 0.3.0-beta01 */
        public val value: String,
        override val mediaType: String? = null,
        override val filename: String? = null,
    ) : FileData()

    /** @since 0.3.0-beta01 */
    public class Bytes(
        bytes: ByteArray,
        override val mediaType: String? = null,
        override val filename: String? = null,
    ) : FileData() {
        private val bytesData: ByteArray = bytes.copyOf()
        /** @since 0.3.0-beta01 */
        public fun toByteArray(): ByteArray = bytesData.copyOf()

        override fun equals(other: Any?): Boolean =
            other is Bytes &&
                bytesData.contentEquals(other.bytesData) &&
                mediaType == other.mediaType &&
                filename == other.filename

        override fun hashCode(): Int {
            var result = bytesData.contentHashCode()
            result = 31 * result + mediaType.hashCode()
            result = 31 * result + filename.hashCode()
            return result
        }

        override fun toString(): String =
            "Bytes(size=${bytesData.size}, mediaType=$mediaType, filename=$filename)"
    }

    @Poko
    /** @since 0.3.0-beta01 */
    public class Url(
        /** @since 0.3.0-beta01 */
        public val value: String,
        override val mediaType: String? = null,
        override val filename: String? = null,
    ) : FileData()
}

@JvmOverloads
/** @since 0.3.0-beta01 */
public fun GeneratedFile(
    data: FileData,
    mediaType: String = data.mediaType ?: "application/octet-stream",
    filename: String? = data.filename,
    providerMetadata: ProviderMetadata = ProviderMetadata.None,
): GeneratedFile = when (data) {
    is FileData.Base64 -> GeneratedFile(
        mediaType = mediaType,
        base64 = data.value,
        filename = filename,
        providerMetadata = providerMetadata,
    )
    is FileData.Bytes -> GeneratedFile(
        mediaType = mediaType,
        base64 = Base64Codec.encode(data.toByteArray()),
        filename = filename,
        providerMetadata = providerMetadata,
    )
    is FileData.Url -> GeneratedFile(
        mediaType = mediaType,
        base64 = "",
        filename = filename,
        providerMetadata = providerMetadata,
        url = data.value,
    )
}

/** @since 0.3.0-beta01 */
public fun ImageGenerationFile(data: FileData): ImageGenerationFile = when (data) {
    is FileData.Base64 -> ImageGenerationFile(
        mediaType = data.mediaType,
        base64 = data.value,
        filename = data.filename,
    )
    is FileData.Bytes -> ImageGenerationFile(
        mediaType = data.mediaType,
        base64 = Base64Codec.encode(data.toByteArray()),
        filename = data.filename,
    )
    is FileData.Url -> ImageGenerationFile(
        mediaType = data.mediaType,
        url = data.value,
        filename = data.filename,
    )
}

/**
 * GeneratedFile read accessors as member-extensions. Use via member-import
 * (`import ai.torad.aisdk.GeneratedFiles.bytes`) or `with(GeneratedFiles) { ... }`.
  * @since 0.3.0-beta01
 */
public object GeneratedFiles {
    /** @since 0.3.0-beta01 */
    public fun GeneratedFile.fileData(): FileData =
        url?.let { FileData.Url(it, mediaType = mediaType, filename = filename) }
            ?: FileData.Base64(base64, mediaType = mediaType, filename = filename)

    /**
     * Decode the inline base64 payload to bytes.
     *
     * @throws IllegalStateException when this file is URL-backed (no inline
     * bytes) — fetch [GeneratedFile.url] to obtain the data. Without this guard a
     * URL-backed file (whose `base64` is `""`) silently decoded to an empty
     * `ByteArray`, a wrong answer indistinguishable from a genuinely empty file.
      * @since 0.3.0-beta01
     */
    public fun GeneratedFile.bytes(): ByteArray {
        if (base64.isEmpty()) {
            check(url == null) {
                "GeneratedFile is URL-backed (mediaType=$mediaType); it has no inline bytes. " +
                    "Fetch `url` to obtain the data, or use bytesOrNull()."
            }
            return ByteArray(0)
        }
        return Base64Codec.decode(base64)
    }

    /**
     * Like [bytes] but returns null for a URL-backed file instead of throwing.
     * @since 0.3.0-beta01
     */
    public fun GeneratedFile.bytesOrNull(): ByteArray? =
        if (base64.isEmpty() && url != null) null else bytes()
}

public typealias GeneratedAudioFile = GeneratedFile

@ExperimentalAiSdkApi
public typealias Experimental_GeneratedImage = GeneratedFile

@ExperimentalAiSdkApi
public typealias Experimental_GenerateImageResult = GenerateImageResult

@ExperimentalAiSdkApi
public typealias Experimental_SpeechResult = GenerateSpeechResult

@ExperimentalAiSdkApi
public typealias Experimental_TranscriptionResult = TranscribeResult

/** @since 0.3.0-beta01 */
public class DefaultGeneratedFile private constructor(
    private var base64Data: String?,
    private var byteArrayData: ByteArray?,
    /** @since 0.3.0-beta01 */
    public val mediaType: String,
) {
    public companion object {
        /** @since 0.3.0-beta01 */
        public fun fromBase64(data: String, mediaType: String): DefaultGeneratedFile =
            DefaultGeneratedFile(base64Data = data, byteArrayData = null, mediaType = mediaType)

        /** @since 0.3.0-beta01 */
        public fun fromBytes(data: ByteArray, mediaType: String): DefaultGeneratedFile =
            DefaultGeneratedFile(base64Data = null, byteArrayData = data.copyOf(), mediaType = mediaType)
    }

    /** @since 0.3.0-beta01 */
    public val base64: String
        get() {
            if (base64Data == null) {
                base64Data = Base64Codec.encode(byteArrayData ?: ByteArray(0))
            }
            return base64Data.orEmpty()
        }

    /** @since 0.3.0-beta01 */
    public val byteArray: ByteArray
        get() {
            if (byteArrayData == null) {
                byteArrayData = Base64Codec.decode(base64Data.orEmpty())
            }
            return byteArrayData?.copyOf() ?: ByteArray(0)
        }

    /** @since 0.3.0-beta01 */
    public fun toGeneratedFile(filename: String? = null, providerMetadata: ProviderMetadata = ProviderMetadata.None): GeneratedFile =
        GeneratedFile(mediaType = mediaType, base64 = base64, filename = filename, providerMetadata = providerMetadata)
}

/** @since 0.3.0-beta01 */
