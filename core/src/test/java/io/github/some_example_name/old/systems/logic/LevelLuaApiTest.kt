package io.github.some_example_name.old.systems.logic

import io.github.some_example_name.old.features.levels.editor.LevelFiles
import org.squiddev.cobalt.LuaTable
import org.squiddev.cobalt.function.LuaFunction
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * API уровня для Lua: что скрипт может попросить показать, когда получает события и
 * что из окружения ему недоступно.
 *
 * Без Gdx: EnvRun создаёт только VM и правила, окна и лог трогают Gdx лишь при вызове
 * из игры.
 */
class LevelLuaApiTest {

    @Test
    fun `цель, прогресс, метка и зона доезжают до экрана`() {
        val env = EnvRun()
        env.setScript(
            """
            objective("Доплыви до метки")
            progress(0, 3)
            marker(10, 20)
            zone(10, 20, 5)
            """.trimIndent()
        )

        assertEquals("", env.run())

        val hud = env.env.rules.hud
        assertEquals("Доплыви до метки", hud.objective)
        assertEquals(0, hud.progress)
        assertEquals(3, hud.progressMax)
        assertTrue(hud.hasMarker)
        assertEquals(10f, hud.markerX)
        assertTrue(hud.hasZone)
        assertEquals(5f, hud.zoneRadius)
    }

    @Test
    fun `marker без аргументов убирает метку`() {
        val env = EnvRun()
        env.setScript("marker(1, 2) marker()")

        assertEquals("", env.run())
        assertFalse(env.env.rules.hud.hasMarker)
    }

    @Test
    fun `событие по клеткам срабатывает один раз`() {
        val env = EnvRun()
        env.setScript(
            """
            onCells(3, function() objective("клетки есть") end)
            """.trimIndent()
        )
        assertEquals("", env.run())

        env.env.rules.step(2)
        assertEquals("", env.env.rules.hud.objective)

        env.env.rules.step(3)
        assertEquals("клетки есть", env.env.rules.hud.objective)

        // Второй раз порог уже пройден — обработчик не повторяется.
        env.setScript("")
        env.env.rules.step(10)
        assertEquals("клетки есть", env.env.rules.hud.objective)
    }

    @Test
    fun `отложенное событие по тикам срабатывает на своём тике`() {
        val env = EnvRun()
        env.setScript("""afterTicks(2, function() objective("прошло два тика") end)""")
        assertEquals("", env.run())

        env.env.rules.step(1)
        assertEquals("", env.env.rules.hud.objective)

        env.env.rules.step(1)
        assertEquals("прошло два тика", env.env.rules.hud.objective)
    }

    @Test
    fun `лимит времени проваливает уровень без своего обработчика`() {
        val env = EnvRun()
        env.setScript("timeLimit(0.05)")
        assertEquals("", env.run())

        repeat(3) { env.env.rules.step(1) }
        assertNotNull(env.env.rules.failReason)
    }

    @Test
    fun `lose отдаёт причину провала`() {
        val env = EnvRun()
        env.setScript("""lose("организм погиб")""")

        assertEquals("", env.run())
        assertEquals("организм погиб", env.env.rules.failReason)
    }

    @Test
    fun `случайность уровня повторяется от сида`() {
        val first = LevelRules().apply { seed(12345) }
        val second = LevelRules().apply { seed(12345) }

        repeat(8) {
            assertEquals(first.randomInt(0, 100), second.randomInt(0, 100))
        }
    }

    @Test
    fun `новый запуск уровня стирает прошлые правила`() {
        val rules = LevelRules()
        rules.setObjective("старое")
        rules.fail("провал")
        rules.setTimeLimit(10f)

        rules.reset()

        assertEquals("", rules.hud.objective)
        assertEquals(null, rules.failReason)
    }

    @Test
    fun `os и io недоступны скрипту уровня`() {
        val env = EnvRun()

        val closed = listOf(
            "os", "io", "debug", "package", "require", "dofile", "loadfile",
            "load", "loadstring", "setfenv", "getfenv", "coroutine", "print", "collectgarbage"
        )
        for (name in closed) {
            assertTrue(env.globals.rawget(name).isNil(), "глобал $name доступен скрипту")
        }
        assertTrue((env.globals.rawget("string") as LuaTable).rawget("dump").isNil(), "string.dump доступен")

        // Обращение к os — обычная ошибка Lua, а не падение игры.
        env.setScript("""os.execute("echo 1")""")
        assertTrue(env.run().isNotEmpty())
    }

    @Test
    fun `чистые библиотеки остались`() {
        val env = EnvRun()

        for (name in listOf("string", "table", "math", "utf8", "pairs", "ipairs", "pcall", "tostring")) {
            assertFalse(env.globals.rawget(name).isNil(), "глобал $name пропал")
        }
    }

    /** java.lang.Math может разойтись на 1 ulp между ПК и Android; в песочнице — StrictMath. */
    @Test
    fun `тригонометрия и степени скрипта идут через StrictMath`() {
        val env = EnvRun()
        env.setScript(
            "s = math.sin(0.7) c = math.cos(0.7) e = math.exp(1.3) p = math.pow(2.5, 1.7) " +
                "a = math.atan2(0.3, -0.8) l = math.log(7, 3)"
        )
        assertEquals("", env.run())

        val g = env.globals
        assertEquals(StrictMath.sin(0.7), g.rawget("s").toDouble())
        assertEquals(StrictMath.cos(0.7), g.rawget("c").toDouble())
        assertEquals(StrictMath.exp(1.3), g.rawget("e").toDouble())
        assertEquals(StrictMath.pow(2.5, 1.7), g.rawget("p").toDouble())
        assertEquals(StrictMath.atan2(0.3, -0.8), g.rawget("a").toDouble())
        assertEquals(StrictMath.log(7.0) / StrictMath.log(3.0), g.rawget("l").toDouble())
    }

    @Test
    fun `глобалы прошлого запуска не доживают до следующего`() {
        val env = EnvRun()
        env.setScript("leftover = 42 function terrain() end")
        assertEquals("", env.run())

        // Второй уровень (или рестарт) — новая машина: ни переменной, ни чужой terrain().
        env.setScript("objective(tostring(leftover))")
        assertEquals("", env.run())
        assertEquals("nil", env.env.rules.hud.objective)
        assertTrue(env.globals.rawget("terrain").isNil())
    }

    @Test
    fun `math random повторяется от сида сессии`() {
        fun sample(seed: Long): String {
            val env = EnvRun()
            env.env.rules.seed(seed)
            env.setScript(
                """
                math.randomseed(123)
                local a = math.random(1, 1000000)
                local b = math.random(6)
                local c = math.random()
                objective(a .. " " .. b .. " " .. c)
                """.trimIndent()
            )
            assertEquals("", env.run())
            return env.env.rules.hud.objective
        }

        assertEquals(sample(7), sample(7))
        assertNotEquals(sample(7), sample(8))
        val (a, b, c) = sample(7).split(" ")
        assertTrue(a.toLong() in 1..1_000_000)
        assertTrue(b.toLong() in 1..6)
        assertTrue(c.toDouble() >= 0.0 && c.toDouble() < 1.0)
    }

    @Test
    fun `вечный цикл обрывается пределом инструкций и проваливает уровень`() {
        val env = EnvRun()
        env.setScript("while true do end")

        assertTrue(env.run().isNotEmpty())
        assertEquals(LevelRules.FAIL_SCRIPT_KEY, env.env.rules.failReason)
    }

    /** pcall ловит ошибки Lua, но не предел: иначе скрипт обходил бы его своим pcall в цикле. */
    @Test
    fun `pcall не обходит предел инструкций`() {
        val env = EnvRun()
        env.setScript("while true do pcall(function() while true do end end) end")

        assertTrue(env.run().isNotEmpty())
        assertEquals(LevelRules.FAIL_SCRIPT_KEY, env.env.rules.failReason)
    }

    @Test
    fun `вечный цикл в обработчике события тоже обрывается`() {
        val env = EnvRun()
        env.setScript("afterTicks(1, function() local i = 0 while true do i = i + 1 end end)")
        assertEquals("", env.run())

        // Обработчик зовут правила; предел проходит сквозь их try до EnvRun.tick (там ловится).
        assertFailsWith<ScriptBudgetExceeded> { env.env.rules.step(0) }
    }

    @Test
    fun `честный цикл укладывается в предел`() {
        val env = EnvRun()
        env.setScript("local s = 0 for i = 1, 20000 do s = s + i end objective(tostring(s))")

        assertEquals("", env.run())
        assertEquals("200010000", env.env.rules.hud.objective)
        assertNull(env.env.rules.failReason)
    }

    @Test
    fun `функции скрипта видны из Kotlin`() {
        val env = EnvRun()
        env.setScript("local t = 0 function tick() t = t + 1 objective(tostring(t)) end")
        assertEquals("", env.run())
        assertTrue(env.globals.rawget("tick") is LuaFunction)
    }

    /**
     * Скрипты встроенных уровней писались под Lua 5.4 (TENUM), а Cobalt — Lua 5.2: каждый
     * обязан хотя бы разбираться. Пустой список — тоже провал: тест проверял бы ничто.
     */
    @Test
    fun `скрипты встроенных уровней разбираются`() {
        val files = File("../assets/levels-builtin").listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue(files.size >= 20, "встроенных уровней ${files.size}, ожидалось больше 20")

        var withLua = 0
        for (file in files.sortedBy { it.name }) {
            val level = LevelFiles.decode(file.readText())
            if (!level.hasLua) continue
            withLua++
            val error = EnvRun().check(level.logic)
            assertNull(error, "${file.name}: ${error?.message}")
        }
        assertTrue(withLua > 0, "ни в одном встроенном уровне нет Lua")
    }

    /** Строки Lua — байты UTF-8: русский текст доходит до экрана и обратно без кракозябр. */
    @Test
    fun `русский текст проходит через Lua в обе стороны`() {
        val env = EnvRun()
        env.setScript("""local s = "Ёжик в тумане" objective(s .. " " .. #s)""")
        assertEquals("", env.run())
        // # — длина в байтах, как в любом Lua: 11 кириллических букв по 2 байта и 2 пробела.
        assertEquals("Ёжик в тумане 24", env.env.rules.hud.objective)

        val error = EnvRun().check("objective(\"привет\")\nlocal = 1")
        assertNotNull(error)
        assertEquals(2, error.line)
    }

    @Test
    fun `ошибка разбора скрипта приходит со строкой`() {
        val error = EnvRun().check("objective(\"a\")\nfunction (")
        assertNotNull(error)
        assertEquals(2, error.line)
        assertNull(EnvRun().check("objective(\"a\")"))
    }
}
