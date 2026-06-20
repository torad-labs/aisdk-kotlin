package ai.torad.aisdk

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
public value class ToolCallId(public val value: String) {
    init { require(value.isNotBlank()) { "ToolCallId must not be blank." } }
    override fun toString(): String = value
}

@JvmInline
@Serializable
public value class ToolName(public val value: String) {
    init { require(value.isNotBlank()) { "ToolName must not be blank." } }
    override fun toString(): String = value
}

@JvmInline
@Serializable
public value class ApprovalId(public val value: String) {
    init { require(value.isNotBlank()) { "ApprovalId must not be blank." } }
    override fun toString(): String = value
}
