package io.github.some_example_name.lwjgl3.demo

import java.io.File
import java.util.zip.CRC32

/**
 * ЖУРНАЛ ИГРОКА: формат, контрольная сумма и разбор.
 *
 * Пишет и исполняет журнал RealBodyDemo (там же живут все действия, чтобы запись и
 * воспроизведение шли через ОДНИ И ТЕ ЖЕ функции), читает PlayerReplay.
 *
 * ЗАЧЕМ. Симптомы вроде «кусок ткани оказался внутри тела» или «частица прилипла к
 * кости» видны руками, а словами их не описать настолько точно, чтобы собрать сцену:
 * всё решает, за какую клетку взялись, когда нажали гребок и по какой траектории
 * вели мышь. Симуляция детерминирована, поэтому журнал всех действий, влияющих на
 * физику, воспроизводит сессию побитово. Контрольные суммы в журнале это проверяют,
 * а не предполагают.
 *
 * ФОРМАТ. Одна строка — одно действие. Первое поле — номер тика, ПЕРЕД которым
 * действие случилось, то есть сколько тиков прошло с запуска окна. Порядок строк —
 * порядок исполнения, и воспроизведение идёт ровно по нему.
 *
 *   HDR <ключ> <значение>   параметры запуска: тело, копии, сид разрушения
 *   <t> R                   сброс
 *   <t> B|G|K|X <0|1>       кости жёсткие / гребок / контакты / разрыв
 *   <t> E <n>               уровень изгиба контура
 *   <t> N <n>               выбрана сцена
 *   <t> S                   запуск выбранной сцены; тики внутри неё идут сами
 *   <t> P | O               пуля | таран
 *   <t> D <id> | U          схватить клетку | отпустить
 *   <t> C <x> <y>           курсор на этом тике; пишется, только пока клетка схвачена
 *   <t> M <spec>            цели мышц с этого тика: «номер:цель,...» или «-»
 *   <t> H <hex>             контрольная сумма состояния после t тиков
 *   <t> MARK <id> <x> <y> … пометка средней кнопкой; x, y — где клетка была НАРИСОВАНА
 *   <t> UNMARK <id>         пометка снята
 *   <t> END                 окно закрыто
 *
 * Время реального мира в журнал не попадает вовсе: пауза, замедление, ускорение и
 * частота кадров меняют только ЧИСЛО тиков за секунду, а не сами тики.
 */
internal object PlayerLog {
    const val FILE = "player-log.txt"

    /** Контрольная сумма пишется раз в столько тиков: секунда при 30 UPS. */
    const val HASH_EVERY = 30

    class Line(val tick: Int, val op: String, val args: List<String>, val raw: String)

    class Parsed(val header: Map<String, String>, val lines: List<Line>)

    fun crc(f: File): String {
        val c = CRC32()
        c.update(f.readBytes())
        return java.lang.Long.toHexString(c.value)
    }

    /**
     * Сумма по позициям и скоростям. Берутся БИТЫ double, а не значения: расхождение
     * в последнем разряде и есть то, что надо поймать, — дальше оно растёт само.
     */
    fun stateHash(n: Int, px: DoubleArray, py: DoubleArray, vx: DoubleArray, vy: DoubleArray): Long {
        var h = 1125899906842597L
        for (i in 0 until n) {
            h = mix(h, java.lang.Double.doubleToLongBits(px[i]))
            h = mix(h, java.lang.Double.doubleToLongBits(py[i]))
            h = mix(h, java.lang.Double.doubleToLongBits(vx[i]))
            h = mix(h, java.lang.Double.doubleToLongBits(vy[i]))
        }
        return h
    }

    private fun mix(h: Long, v: Long): Long {
        var x = (h xor v) * -7046029254386353131L
        x = x xor (x ushr 31)
        return x
    }

    fun parse(f: File): Parsed {
        val header = LinkedHashMap<String, String>()
        val lines = ArrayList<Line>()
        for (raw in f.readLines(Charsets.UTF_8)) {
            val s = raw.trim()
            if (s.isEmpty() || s.startsWith("#")) continue
            if (s.startsWith("HDR ")) {
                val rest = s.substring(4)
                val sp = rest.indexOf(' ')
                if (sp > 0) header[rest.substring(0, sp)] = rest.substring(sp + 1)
                continue
            }
            val p = s.split(' ')
            val tick = p[0].toIntOrNull() ?: continue
            if (p.size < 2) continue
            lines.add(Line(tick, p[1], p.subList(2, p.size), s))
        }
        return Parsed(header, lines)
    }
}
