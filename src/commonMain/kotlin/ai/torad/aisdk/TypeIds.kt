package ai.torad.aisdk

import kotlinx.serialization.Serializable
import kotlin.ExperimentalStdlibApi
import kotlin.jvm.JvmExposeBoxed
import kotlin.jvm.JvmInline

@JvmInline
@Serializable
@OptIn(ExperimentalStdlibApi::class)
@JvmExposeBoxed
public value class ToolCallId(public val value: String) {
    init { require(value.isNotBlank()) { "ToolCallId must not be blank." } }
    override fun toString(): String = value

    public companion object {
        @JvmExposeBoxed
        @AiSdkJvmStatic
        /** @since 0.3.0-beta01 */
        public fun of(value: String): ToolCallId = ToolCallId(value)
    }
}

@JvmInline
@Serializable
@OptIn(ExperimentalStdlibApi::class)
@JvmExposeBoxed
public value class ToolName(public val value: String) {
    init { require(value.isNotBlank()) { "ToolName must not be blank." } }
    override fun toString(): String = value

    public companion object {
        @JvmExposeBoxed
        @AiSdkJvmStatic
        /** @since 0.3.0-beta01 */
        public fun of(value: String): ToolName = ToolName(value)
    }
}

@JvmInline
@Serializable
@OptIn(ExperimentalStdlibApi::class)
@JvmExposeBoxed
public value class ApprovalId(public val value: String) {
    init { require(value.isNotBlank()) { "ApprovalId must not be blank." } }
    override fun toString(): String = value

    public companion object {
        @JvmExposeBoxed
        @AiSdkJvmStatic
        /** @since 0.3.0-beta01 */
        public fun of(value: String): ApprovalId = ApprovalId(value)
    }
}
