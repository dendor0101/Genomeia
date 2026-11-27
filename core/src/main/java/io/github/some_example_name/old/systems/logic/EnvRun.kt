package io.github.some_example_name.old.systems.logic

import ai.tenum.lua.runtime.LuaFunction
import ai.tenum.lua.runtime.LuaNativeFunction
import ai.tenum.lua.runtime.LuaNumber
import ai.tenum.lua.runtime.LuaValue
import ai.tenum.lua.lexer.Lexer
import ai.tenum.lua.parser.Parser
import ai.tenum.lua.compiler.Compiler
import ai.tenum.lua.parser.ParserException
import ai.tenum.lua.vm.LuaVmImpl
import ai.tenum.lua.vm.errorhandling.LuaException
import ai.tenum.lua.vm.errorhandling.LuaRuntimeError

class EnvRun {
    val globals = LuaVmImpl()
    val env = LogicEnv()

    var logicScript = ""

    init {
        globals.globals.set("env", LuaNativeFunction { args -> listOf(LuaNumber.of(5))})
        globals.globals.set("getCells", env.getCellsCount)
        globals.globals.set("popup", env.popup)
        globals.globals.set("win", env.win)
        globals.globals.set("lose", env.lose)

        // Что показать игроку
        globals.globals.set("objective", env.objective)
        globals.globals.set("progress", env.progress)
        globals.globals.set("marker", env.marker)
        globals.globals.set("zone", env.zone)
        globals.globals.set("hint", env.hint)
        globals.globals.set("timeLimit", env.timeLimit)
        globals.globals.set("setMovable", env.setMovable)

        // События вместо опроса мира каждый тик
        globals.globals.set("onCells", env.onCells)
        globals.globals.set("onDestination", env.onDestination)
        globals.globals.set("onTimeout", env.onTimeout)
        globals.globals.set("afterTicks", env.afterTicks)

        // Мир: еда, случайность, отладочный вывод
        globals.globals.set("world", env.world)
        globals.globals.set("spawnFood", env.spawnFood)
        globals.globals.set("random", env.random)
        globals.globals.set("log", env.log)
        globals.globals.set("bundle", env.bundle)

        sandbox()
        //logicScript = """function tick() popup("hi") end"""
    }

    private fun sandbox() {
        for (name in REMOVED_GLOBALS) globals.globals.remove(name)
    }

    fun run(): String {
        // Новый запуск уровня (в том числе рестарт) — правила и легаси-триггеры заново.
        env.rules.reset()
        env.resetTriggers()
        try {
            globals.execute(logicScript)
            return ""
        }
        catch (e: LuaRuntimeError) {
            return e.message ?: ""
        }
        catch (e: LuaException) {
            return e.message ?: ""
        }
    }

    /** Разбор скрипта без запуска. null — ошибок нет. */
    fun check(): ScriptError? {
        return try {
            val lexer = Lexer(logicScript, "check")
            val tokens = lexer.scanTokens()

            val parser = Parser(tokens)
            val ast = parser.parse()

            val compiler = Compiler(sourceFilename = "check")
            compiler.compile(ast, hasVararg = true)

            null
        }
        catch (e: ParserException) {
            ScriptError(e.message.toString(), e.token.line)
        }
        catch (e: Exception) {
            ScriptError(e.message.toString(), line = -1)
        }
    }

    fun cells(): String {
        return executeFunc("cells")
    }

    fun terrain(): String {
        return executeFunc("terrain")
    }

    fun tick(): String {
        return executeFunc("tick")
    }

    fun setScript(text: String) {
        logicScript = text
    }

    fun executeFunc(name: String): String {
        try {
            env.execute()
            val tick = globals.globals.get(name)
            if (tick == null) {
                return "Function ${name} hasn't found"
            }
            else if (tick !is LuaFunction) {
                return "name ${name} reserved to system function"
            }
            tick.call(listOf())
            return ""
        }
        catch (e: LuaException) {
            return e.message ?: ""
        }
        catch (e: LuaRuntimeError) {
            return e.message ?: ""
        }
    }

    private companion object {
        /**
         * Что вырезаем из глобальных имён. `load`/`require`/`dofile`/`loadfile` умеют
         * читать файлы и обходить эту чистку заново; `debug` лезет во внутренности VM.
         */
        val REMOVED_GLOBALS = listOf(
            "os", "io", "debug", "package",
            "require", "dofile", "loadfile", "load"
        )
    }
}

/**
 * Ошибка разбора скрипта уровня. [message] — текст парсера TENUM (он по-английски и не
 * переводится), [line] — строка скрипта, -1 — неизвестна. Фразу для игрока собирает
 * редактор уровней из бандла: здесь Gdx нет, класс работает и в тестах :core.
 */
class ScriptError(val message: String, val line: Int)
