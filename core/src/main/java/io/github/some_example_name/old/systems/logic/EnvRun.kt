package io.github.some_example_name.old.systems.logic

import org.squiddev.cobalt.Constants
import org.squiddev.cobalt.LuaError
import org.squiddev.cobalt.LuaState
import org.squiddev.cobalt.LuaTable
import org.squiddev.cobalt.LuaThread
import org.squiddev.cobalt.LuaValue
import org.squiddev.cobalt.ValueFactory.valueOf
import org.squiddev.cobalt.compiler.CompileException
import org.squiddev.cobalt.compiler.LoadState
import org.squiddev.cobalt.debug.DebugFrame
import org.squiddev.cobalt.debug.DebugHook
import org.squiddev.cobalt.debug.DebugState
import org.squiddev.cobalt.function.LibFunction
import org.squiddev.cobalt.function.LuaFunction
import org.squiddev.cobalt.lib.CoreLibraries

/**
 * Скрипт уровня на Lua (Cobalt): машина, песочница и вызовы из потока симуляции.
 *
 * ПОЧЕМУ COBALT
 * -------------
 * Cobalt — форк LuaJ из мода CC: Tweaked, лицензия MIT (совместима с GPL-3 игры), Lua 5.2,
 * без своих зависимостей. Раньше здесь был TENUM (Lua 5.4): его лицензия TSAL 1.0 запрещает
 * раздавать функциональность библиотеки третьим лицам и несовместима с GPL-3 (28.11).
 * Скрипты встроенных уровней ничего из Lua 5.3+ не используют.
 *
 * ДЕТЕРМИНИЗМ
 * -----------
 *  - Машина Lua — новая на каждый запуск уровня ([run]): глобалы прошлого уровня или прошлой
 *    попытки не доживают до следующей. Создаётся лениво: обычной игре без уровней она не нужна.
 *  - math.random и random() — из генератора уровня с сидом сессии (LevelRules.seed): свой
 *    генератор Cobalt сеется от часов. math.randomseed — пустой: сид выбирает не скрипт.
 *  - Тригонометрия, exp, log, pow — через StrictMath: java.lang.Math может отличаться на
 *    1 ulp между ПК и Android, и .bug с телефона разошёлся бы на ПК (CLAUDE.md, «Детерминизм»).
 *  - Число инструкций Lua на один вход из Kotlin ограничено ([INSTRUCTION_BUDGET]): вечный
 *    цикл в скрипте игрока иначе вешал поток симуляции. Счётчик — отладочный хук Cobalt «раз
 *    в N инструкций», число инструкций детерминировано, и повтор обрывает скрипт на той же.
 *    Прерывание Cobalt по флагу (LuaState.interrupt) не годится: его дёргают по таймеру.
 *  - ОТКРЫТЫЙ РИСК: pairs() по таблице с ключами-таблицами или функциями идёт в порядке их
 *    identityHashCode, а он от запуска к запуску разный (ключи-строки и числа — порядок
 *    постоянный). Скрипты встроенных уровней так не делают; tostring(таблицы) — тоже адрес.
 */
class EnvRun {
    val env = LogicEnv()

    /** Текущая машина Lua; новая на каждый [run], до первого обращения её нет. */
    private var state: LuaState? = null

    val lua: LuaState get() = state ?: createState().also { state = it }

    /** Глобальная таблица текущей машины. */
    val globals: LuaTable get() = lua.globals()

    private var logicScript = ""

    /** Сколько срабатываний хука (по [INSTRUCTIONS_PER_CHECK] инструкций) осталось до обрыва. */
    private var checksLeft = CHECKS_PER_ENTRY

    /**
     * Скрипт оборван пределом: уровень провален, а машину с прерванным посреди функции
     * стеком больше не трогаем до следующего [run].
     */
    private var halted = false

    fun setScript(text: String) {
        logicScript = text
    }

    /** Запуск уровня (и рестарт): новая машина, правила и события с чистого листа. */
    fun run(): String {
        env.rules.reset()
        env.resetTriggers()
        val s = createState()
        state = s
        halted = false
        checksLeft = CHECKS_PER_ENTRY
        return guarded {
            val chunk = LoadState.load(s, logicScript.byteInputStream(), CHUNK_NAME, s.globals())
            LuaThread.runMain(s, chunk)
        }
    }

    /** Разбор скрипта без запуска (кнопка «Проверить»). null — ошибок нет. */
    fun check(text: String): ScriptError? = try {
        val s = LuaState.builder().build()
        LoadState.load(s, text.byteInputStream(), CHUNK_NAME, LuaTable())
        null
    } catch (e: CompileException) {
        val message = luaMessageUtf8(e.message.orEmpty())
        val line = LINE_IN_MESSAGE.find(message)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        ScriptError(message, line)
    } catch (e: Exception) {
        ScriptError(e.message.toString(), line = -1)
    }

    /**
     * Тик уровня: события правил (ровно один шаг LevelRules за тик) и функция tick() скрипта.
     * cells() и terrain() зовутся отсюда же, через события, — сами они шаг не делают: раньше
     * каждый их вызов делал ещё один step(), и afterTicks/timeLimit съезжали на тик.
     */
    fun tick(): String {
        if (halted) return ""
        checksLeft = CHECKS_PER_ENTRY
        return guarded {
            env.execute(::callFunction)
            callFunction("tick")
        }
    }

    /** Функция скрипта по имени; нет такой — ничего, без строк и аллокаций на каждый тик. */
    private fun callFunction(name: String) {
        val s = state ?: return
        val fn = s.globals().rawget(name) as? LuaFunction ?: return
        LuaThread.runMain(s, fn)
    }

    /**
     * Ошибку скрипта не пробрасываем в тик: иначе один кривой уровень валит мир. Ловим всё:
     * нативные функции API могут бросить и не-Lua исключение.
     */
    private inline fun guarded(block: () -> Unit): String = try {
        block()
        ""
    } catch (e: ScriptBudgetExceeded) {
        halted = true
        env.reportScriptTooLong()
        SCRIPT_TOO_LONG
    } catch (e: LuaError) {
        env.reportScriptError(e)
        // Текст ошибки Lua собран из байтов скрипта — см. LuaText.kt.
        e.message?.let(::luaMessageUtf8) ?: e.toString()
    } catch (e: Exception) {
        env.reportScriptError(e)
        e.message ?: e.toString()
    }

    private fun createState(): LuaState {
        val s = LuaState.builder().build()
        CoreLibraries.standardGlobals(s)
        val g = s.globals()
        // Обработчики событий правил зовутся этой же машиной.
        env.rules.lua = s

        g.rawset("getCells", env.getCellsCount)
        g.rawset("popup", env.popup)
        g.rawset("win", env.win)
        g.rawset("lose", env.lose)

        // Что показать игроку
        g.rawset("objective", env.objective)
        g.rawset("progress", env.progress)
        g.rawset("marker", env.marker)
        g.rawset("zone", env.zone)
        g.rawset("hint", env.hint)
        g.rawset("timeLimit", env.timeLimit)
        g.rawset("setMovable", env.setMovable)

        // События вместо опроса мира каждый тик
        g.rawset("onCells", env.onCells)
        g.rawset("onDestination", env.onDestination)
        g.rawset("onTimeout", env.onTimeout)
        g.rawset("afterTicks", env.afterTicks)

        // Мир: еда, случайность, отладочный вывод
        g.rawset("world", env.world)
        g.rawset("spawnFood", env.spawnFood)
        g.rawset("random", env.random)
        g.rawset("log", env.log)
        g.rawset("bundle", env.bundle)

        (g.rawget("math") as? LuaTable)?.let(::makeMathDeterministic)
        (g.rawget("string") as? LuaTable)?.rawset("dump", Constants.NIL)
        for (name in REMOVED_GLOBALS) g.rawset(name, Constants.NIL)

        installInstructionBudget(s)
        return s
    }

    /** Случайность — из генератора уровня, трансцендентные функции — через StrictMath. */
    private fun makeMathDeterministic(math: LuaTable) {
        math.rawset("random", env.mathRandom)
        // Сид — сессии мира, скрипт его не выбирает: иначе повтор зависел бы от скрипта.
        math.rawset("randomseed", LibFunction.createV { _, _ -> Constants.NONE })
        math.rawset("sin", strict1(StrictMath::sin))
        math.rawset("cos", strict1(StrictMath::cos))
        math.rawset("tan", strict1(StrictMath::tan))
        math.rawset("asin", strict1(StrictMath::asin))
        math.rawset("acos", strict1(StrictMath::acos))
        math.rawset("sinh", strict1(StrictMath::sinh))
        math.rawset("cosh", strict1(StrictMath::cosh))
        math.rawset("tanh", strict1(StrictMath::tanh))
        math.rawset("exp", strict1(StrictMath::exp))
        math.rawset("log10", strict1(StrictMath::log10))
        math.rawset("pow", LibFunction.create { _, x, y -> valueOf(StrictMath.pow(x.checkDouble(), y.checkDouble())) })
        math.rawset("atan2", LibFunction.create { _, y, x -> valueOf(StrictMath.atan2(y.checkDouble(), x.checkDouble())) })
        // atan(y [, x]) и log(x [, base]) — как в Lua 5.2, но через StrictMath.
        math.rawset("atan", LibFunction.create { _, y, x ->
            valueOf(if (x.isNil()) StrictMath.atan(y.checkDouble()) else StrictMath.atan2(y.checkDouble(), x.checkDouble()))
        })
        math.rawset("log", LibFunction.create { _, x, base ->
            val v = x.checkDouble()
            valueOf(if (base.isNil()) StrictMath.log(v) else StrictMath.log(v) / StrictMath.log(base.checkDouble()))
        })
    }

    private fun strict1(f: (Double) -> Double): LuaFunction =
        LibFunction.create { _, x: LuaValue -> valueOf(f(x.checkDouble())) }

    /**
     * Хук «раз в [INSTRUCTIONS_PER_CHECK] инструкций» на главный поток Lua. Корутины (они
     * наследуют хук) из песочницы всё равно убраны: обработчики событий и tick() их не требуют.
     */
    private fun installInstructionBudget(s: LuaState) {
        val budget = object : DebugHook {
            override fun onCount(state: LuaState, ds: DebugState, frame: DebugFrame) {
                if (--checksLeft < 0) throw ScriptBudgetExceeded()
            }
        }
        s.mainThread.debugState.setHook(budget, false, false, false, INSTRUCTIONS_PER_CHECK)
    }

    companion object {
        /**
         * Инструкций Lua на один вход из Kotlin (запуск уровня или тик). Скрипты гайдов
         * исполняют десятки инструкций за тик. Предел выбран под прежний TENUM (замер 27.11:
         * миллион инструкций за 0.33 с на ПК), честный цикл на 20 тысяч итераций в него
         * укладывается; Cobalt быстрее, так что зависший скрипт стоит игре ещё меньше.
         */
        const val INSTRUCTION_BUDGET = 200_000

        /** Хук зовётся раз в столько инструкций: вызов хука дороже инструкции. */
        const val INSTRUCTIONS_PER_CHECK = 1000

        private const val CHECKS_PER_ENTRY = INSTRUCTION_BUDGET / INSTRUCTIONS_PER_CHECK

        /** Что вернуть вызывающему, когда скрипт оборван пределом. */
        const val SCRIPT_TOO_LONG = "level script exceeded the instruction budget"

        /** Имя куска для сообщений об ошибках: «level:3: …». */
        private const val CHUNK_NAME = "=level"

        /** Номер строки в сообщении компилятора Cobalt («level:3: unexpected symbol …»). */
        private val LINE_IN_MESSAGE = Regex(""":(\d+):""")

        /**
         * Что вырезаем из глобальных имён. `load`/`loadstring` собирают код из строки в
         * обход проверок редактора, `setfenv`/`getfenv` подменяют окружение функций,
         * `coroutine` — лишний вход для хука предела. Файлов, ОС и `debug` в стандартном
         * наборе Cobalt нет вовсе (`CoreLibraries.standardGlobals`).
         */
        val REMOVED_GLOBALS = listOf("load", "loadstring", "setfenv", "getfenv", "coroutine")
    }
}

/**
 * Скрипт уровня превысил предел инструкций ([EnvRun.INSTRUCTION_BUDGET]). Error, а не Exception, —
 * намеренно: pcall и coroutine в Cobalt ловят Exception и VirtualMachineError (ProtectedCall,
 * LuaThread), и обычное исключение скрипт поймал бы своим pcall и крутился дальше. Error
 * проходит и сквозь LevelRules.call до EnvRun, где его ловят. Стек не собираем: он не нужен.
 */
class ScriptBudgetExceeded : Error(EnvRun.SCRIPT_TOO_LONG) {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * Ошибка разбора скрипта уровня. [message] — текст компилятора Cobalt (он по-английски и не
 * переводится), [line] — строка скрипта, -1 — неизвестна. Фразу для игрока собирает
 * редактор уровней из бандла: здесь Gdx нет, класс работает и в тестах :core.
 */
class ScriptError(val message: String, val line: Int)
