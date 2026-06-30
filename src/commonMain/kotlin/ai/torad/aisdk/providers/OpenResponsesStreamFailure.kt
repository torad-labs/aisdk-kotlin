package ai.torad.aisdk.providers

import ai.torad.aisdk.JsonAccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object OpenResponsesStreamFailure {
    fun message(response: JsonObject): String {
        val error = JsonAccess.obj(response, "error")
        val detail = (error?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (error?.get("code") as? JsonPrimitive)?.contentOrNull
            ?: (response["status"] as? JsonPrimitive)?.contentOrNull
        return detail?.let { "Open Responses stream failed: $it" }
            ?: "Open Responses stream failed."
    }
}
