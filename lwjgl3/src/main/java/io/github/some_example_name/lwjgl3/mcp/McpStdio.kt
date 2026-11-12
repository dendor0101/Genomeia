package io.github.some_example_name.lwjgl3.mcp

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*

/** Этот режим запускается до LWJGL: stdout принадлежит исключительно MCP. */
object McpStdio {
    private val tools by lazy {
        val resource = checkNotNull(javaClass.getResource("/genome-mcp-tools.json"))
        Json.parseToJsonElement(resource.readText()).jsonArray
    }
    private val protocols = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
    private const val MAX_LINE = 1024 * 1024

    @JvmStatic fun run() {
        val input = System.`in`.buffered()
        val output = System.out.bufferedWriter(Charsets.UTF_8)
        val scale = ScaleQuestion()
        fun emit(reply: JsonObject) {
            output.write(reply.toString())
            output.newLine()
            output.flush()
        }
        var initialized = false
        while (true) {
            val line = ByteArrayOutputStream()
            var c = input.read()
            if (c == -1) return
            var oversized = false
            while (c != -1 && c != 10) {
                if (line.size() < MAX_LINE) line.write(c) else oversized = true
                c = input.read()
            }
            var id: JsonElement = JsonNull
            val response = try {
                require(!oversized) { "Request exceeds 1 MiB" }
                val message = Json.parseToJsonElement(line.toString(Charsets.UTF_8)).jsonObject
                if ("method" !in message) {
                    scale.answer(message)?.let(::emit)
                    continue
                }
                if (!message.containsKey("id")) {
                    if (message["method"]?.jsonPrimitive?.content == "notifications/cancelled") {
                        scale.cancel((message["params"] as? JsonObject)?.get("requestId"))
                    }
                    continue
                }
                id = message.getValue("id")
                require(message.string("jsonrpc") == "2.0")
                val method = message.string("method")
                val params = message["params"]?.jsonObject ?: obj()
                val result = when (method) {
                    "initialize" -> {
                        val requested = params.string("protocolVersion")
                        val negotiated = protocols.find { it == requested } ?: protocols.first()
                        scale.configure(negotiated, params["capabilities"] as? JsonObject)
                        initialized = true
                        obj("protocolVersion" to negotiated,
                            "capabilities" to obj("tools" to obj()),
                            "serverInfo" to obj("name" to "Genomeia", "version" to "0.2.0"),
                            "instructions" to BodyLayouts.RULES)
                    }
                    "ping" -> obj()
                    "tools/list" -> { require(initialized); obj("tools" to tools) }
                    "tools/call" -> {
                        require(initialized)
                        val name = params.string("name")
                        val spec = tools.firstOrNull { it.jsonObject.string("name") == name } ?: error("Unknown tool: $name")
                        val schema = spec.jsonObject.getValue("inputSchema").jsonObject
                        val args = params["arguments"]?.jsonObject ?: obj()
                        val properties = schema.getValue("properties").jsonObject
                        val missing = schema.getValue("required").jsonArray.map { it.jsonPrimitive.content } - args.keys
                        require(missing.isEmpty() && args.keys.all { it in properties }) { "Check required and allowed arguments for $name" }
                        if (name == "request_body_scale") {
                            emit(scale.request(id))
                            continue
                        }
                        try { GameBridge.call(params) } catch (e: Exception) {
                            failure(e.message ?: "Game disconnected. Check --enable-mcp and get_status.")
                        }
                    }
                    else -> {
                        output.write(errorResponse(id, -32601, "Unknown method: $method").toString())
                        output.newLine()
                        output.flush()
                        continue
                    }
                }
                obj("jsonrpc" to "2.0", "id" to id, "result" to result)
            } catch (e: Exception) {
                errorResponse(id, if (id == JsonNull) -32700 else -32602, e.message ?: "Invalid request")
            }
            output.write(response.toString())
            output.newLine()
            output.flush()
        }
    }

    private fun errorResponse(id: JsonElement, code: Int, message: String) =
        obj("jsonrpc" to "2.0", "id" to id, "error" to obj("code" to code, "message" to message.take(500)))
}
