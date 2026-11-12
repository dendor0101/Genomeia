package io.github.some_example_name.lwjgl3.mcp

import java.util.UUID
import kotlinx.serialization.json.*

/**
 * Route form responses back to the originating tools/call; the stdio loop can still answer ping.
 */
internal class ScaleQuestion {
    private val pending = mutableMapOf<String, JsonElement>()
    private var forms = false
    private val question =
        "Какой максимальный размер взрослого организма допустим в клетках мира? Укажите ширину и высоту, например 4 × 4."

    fun configure(protocol: String, capabilities: JsonObject?) {
        pending.clear()
        val elicitation = capabilities?.get("elicitation") as? JsonObject
        forms =
            protocol >= "2025-06-18" &&
                elicitation != null &&
                (elicitation.isEmpty() || "form" in elicitation)
    }

    fun request(callId: JsonElement): JsonObject {
        if (!forms)
            return obj(
                "jsonrpc" to "2.0",
                "id" to callId,
                "result" to
                    result(
                        obj(
                            "status" to "ask_user",
                            "question" to question,
                            "instruction" to "Ask in chat and wait for both dimensions.",
                        )
                    ),
            )
        require(pending.size < 8) { "Too many unanswered scale questions" }
        val id = "scale:" + UUID.randomUUID()
        pending[id] = callId
        fun dimension(title: String) =
            obj("type" to "number", "title" to title, "minimum" to 0.4, "maximum" to 128)
        val schema =
            obj(
                "type" to "object",
                "properties" to
                    obj("width" to dimension("Ширина"), "height" to dimension("Высота")),
                "required" to listOf("width", "height"),
            )
        return obj(
            "jsonrpc" to "2.0",
            "id" to id,
            "method" to "elicitation/create",
            "params" to obj("message" to question, "requestedSchema" to schema),
        )
    }

    fun cancel(callId: JsonElement?) {
        pending.values.remove(callId)
    }

    fun answer(message: JsonObject): JsonObject? {
        val id = pending.remove(message["id"]?.jsonPrimitive?.content) ?: return null
        val answer = message["result"] as? JsonObject
        val action = answer?.get("action")?.jsonPrimitive?.contentOrNull
        val body =
            when (action) {
                "accept" -> {
                    val data = answer["content"] as? JsonObject
                    val w = (data?.get("width") as? JsonPrimitive)?.doubleOrNull
                    val h = (data?.get("height") as? JsonPrimitive)?.doubleOrNull
                    if (w != null && h != null && w in 0.4..128.0 && h in 0.4..128.0)
                        result(obj("status" to "accepted", "width" to w, "height" to h))
                    else failure("The form must contain two dimensions between 0.4 and 128.")
                }
                "decline",
                "cancel" ->
                    result(
                        obj(
                            "status" to action,
                            "instruction" to "Stop design until dimensions are provided.",
                        )
                    )
                else ->
                    failure("The scale form failed. Ask the user in chat and wait for an answer.")
            }
        return obj("jsonrpc" to "2.0", "id" to id, "result" to body)
    }
}
