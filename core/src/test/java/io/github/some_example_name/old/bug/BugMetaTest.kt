package io.github.some_example_name.old.bug

import com.badlogic.gdx.utils.JsonReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BugMetaTest {

    private val crashManifest = mapOf(
        "kind" to "crash",
        "game" to "alpha-0.2.4",
        "created" to "2026-10-12T13:44:34+0300",
        "deviceId" to "7cb9f803c736",
        "platform" to "Android",
        "os" to "Linux 5.10",
        "physics.engine" to "rust",
        "device.model" to "Pixel \"7\"",
        "device.sdk" to "34",
        "gpu" to "Mali-G710",
        "screen" to "1080x2400",
        "description" to "упало\tпосле \\ рестарта",
        "crash.exception" to "java.lang.IllegalStateException",
        "crash.message" to "сетка: живой частицы 1537 нет в сетке",
        "crash.thread" to "Simulation-Main-Thread",
        "crash.signature" to "0123456789abcdef",
        "crash.top" to "GridManager.verifyIntegrity:386 | SimulationSystem.tick:193",
        "final.epoch" to "1",
        "final.tick" to "5082",
        "journal.ui" to "179",
        "images" to "2",
        "screenshot" to "true"
    )

    @Test
    fun `meta json разбирается и несёт поля для группировки`() {
        val json = JsonReader().parse(BugMeta.json(crashManifest, "x-crash.bug", 1234))

        assertEquals(BugMeta.SCHEMA, json.getInt("schema"))
        assertEquals("crash", json.getString("kind"))
        assertEquals(1234L, json.getLong("size"))
        assertEquals("Android", json.get("platform").getString("type"))
        assertEquals("rust", json.get("platform").getString("physics"))
        assertEquals("Pixel \"7\"", json.get("device").getString("model"))
        assertEquals("Mali-G710", json.get("device").getString("gpu"))

        val crash = json.get("crash")
        assertEquals("0123456789abcdef", crash.getString("signature"))
        assertEquals(listOf("GridManager.verifyIntegrity:386", "SimulationSystem.tick:193"), crash.get("top").asStringArray().toList())

        assertEquals(5082L, json.get("session").getLong("tick"))
        assertEquals("упало\tпосле \\ рестарта", json.get("report").getString("description"))
        assertEquals(2L, json.get("report").getLong("images"))
        assertTrue(json.get("report").getBoolean("screenshot"))
    }

    @Test
    fun `у отчёта без краша crash равен null`() {
        val json = JsonReader().parse(BugMeta.json(mapOf("kind" to "report"), "x-report.bug", null))
        assertTrue(json.get("crash").isNull)
        assertEquals(0L, json.get("report").getLong("images"))
        // Отчёт без движка физики (до 27.11) — поля нет.
        assertTrue(json.get("platform").get("physics") == null)
    }
}
