package io.github.some_example_name.physics.lab.stands.viewer

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.physics.lab.stands.CONTACT_DISTANCE
import io.github.some_example_name.physics.lab.stands.PHYSICS_PER_GAME
import io.github.some_example_name.physics.lab.stands.Rule
import io.github.some_example_name.physics.lab.stands.StandRun
import io.github.some_example_name.physics.lab.stands.StandWorld
import io.github.some_example_name.physics.lab.stands.TERRAIN_STEP
import io.github.some_example_name.render.demo.PrimitiveCanvas

/** Отметка нарушения, которая держится на экране несколько тиков: за один кадр её не разглядеть. */
internal class StickyMark(val rule: Rule, val x: Double, val y: Double, val tick: Int)

/**
 * КАРТИНКА СЦЕНЫ: мир стенда в прямоугольнике окна и камера над ним.
 *
 * Клетки рисуются кружками радиуса касания, а не радиуса клетки игры: два тела касаются ровно
 * тогда, когда касаются их кружки, и проникновение видно глазом без подсказок. Радиус касания у
 * клетки контура берётся у самого решателя (половина её самой длинной связи по контуру), у
 * клетки внутри ткани и у свободной частицы — радиус клетки на 0.6: так видны и разные длины
 * связей, и разные радиусы. Ткань — закрашенные треугольники: дыра в ткани — это порванные связи.
 * У растущего тела бледным нарисовано взрослое тело, каким оно должно вырасти (P — выключить).
 * Спящее рисуется темнее, костная клетка — светлой, мышца — цветом своей группы, который
 * светлеет при сокращении и темнеет при расслаблении.
 */
internal class SceneView {
    /** Центр обзора в единицах игры и масштаб в пикселях на единицу. */
    var camX = 0.0; var camY = 0.0; var zoom = 20.0
    var showLinks = true
    var showCells = true
    /** Рисовать ли у растущего тела бледный контур взрослого: во что оно должно вырасти. */
    var showPreview = true

    private var left = 0f; private var bottom = 0f; private var width = 1f; private var height = 1f

    private var sx = FloatArray(0)
    private var sy = FloatArray(0)
    /** Вид клетки: -1 не клетка тела, 0 ткань, 1 кость, 2 + группа — мышца. */
    private var kind = IntArray(0)
    private var bodyOf = IntArray(0)
    private var command = FloatArray(0)
    private var dim = BooleanArray(0)
    /** Радиус кружка частицы на экране, пиксели. */
    private var size = FloatArray(0)
    private val scratch = Color()

    val marks = ArrayList<StickyMark>()

    fun place(left: Float, bottom: Float, width: Float, height: Float) {
        this.left = left; this.bottom = bottom; this.width = width; this.height = height
    }

    fun contains(px: Float, py: Float): Boolean = px >= left && px <= left + width && py >= bottom && py <= bottom + height

    fun screenX(gx: Double): Float = (left + width / 2 + (gx - camX) * zoom).toFloat()
    fun screenY(gy: Double): Float = (bottom + height / 2 + (gy - camY) * zoom).toFloat()
    fun worldX(px: Float): Double = camX + (px - left - width / 2) / zoom
    fun worldY(py: Float): Double = camY + (py - bottom - height / 2) / zoom

    /** Камера сама держит сцену в кадре, пока человек не сдвинул её колесом или мышью (C — вернуть). */
    var follow = true
    private var startBox = doubleArrayOf(-5.0, -5.0, 5.0, 5.0)
    private var fixedView = false

    /** Камера по рамке сцены: по заданной сценой или по содержимому. */
    fun fit(w: StandWorld) {
        val box = w.view ?: w.contentBox()
        fixedView = w.view != null
        startBox = box.copyOf()
        follow = true
        aim(box, 1.0)
    }

    private fun aim(box: DoubleArray, share: Double) {
        val bw = maxOf(1.0, box[2] - box[0]); val bh = maxOf(1.0, box[3] - box[1])
        val targetZoom = minOf(width / bw, height / bh) * 0.92
        camX += ((box[0] + box[2]) / 2 - camX) * share
        camY += ((box[1] + box[3]) / 2 - camY) * share
        zoom += (targetZoom - zoom) * share
    }

    /**
     * Держать сцену в кадре: рамка — стартовая плюс всё, что из неё выехало. Улетевший обломок
     * кадр не растягивает без меры: дальше шести стартовых размеров от старта камера не смотрит.
     * У сцен со своей рамкой (рельеф, стенка мира) кадр постоянный.
     */
    fun track(w: StandWorld, snap: Boolean) {
        if (!follow || fixedView) return
        val st = w.world.state
        val s = startBox
        // Шесть стартовых размеров: тело, отлетевшее от удара на двух связях за тик, уходит на десятки единиц, и при
        // запасе в два размера оно пропадало из кадра — на сцене оставался один налетевший.
        val reach = 6.0 * maxOf(s[2] - s[0], s[3] - s[1])
        val lx = s[0] - reach; val ly = s[1] - reach; val hx = s[2] + reach; val hy = s[3] + reach
        var x0 = s[0]; var y0 = s[1]; var x1 = s[2]; var y1 = s[3]
        for (i in 0 until st.n) {
            if (st.cellDead[i] || st.cellUnborn[i]) continue
            val x = st.px[i] / PHYSICS_PER_GAME; val y = st.py[i] / PHYSICS_PER_GAME
            if (x < lx || x > hx || y < ly || y > hy) continue
            if (x - 1.0 < x0) x0 = x - 1.0; if (x + 1.0 > x1) x1 = x + 1.0
            if (y - 1.0 < y0) y0 = y - 1.0; if (y + 1.0 > y1) y1 = y + 1.0
        }
        aim(doubleArrayOf(x0, y0, x1, y1), if (snap) 1.0 else 0.12)
    }

    /** Масштаб вокруг точки экрана: то, что под курсором, остаётся под курсором. */
    fun zoomAt(px: Float, py: Float, factor: Double) {
        follow = false
        val wx = worldX(px); val wy = worldY(py)
        zoom = (zoom * factor).coerceIn(0.5, 400.0)
        camX = wx - (px - left - width / 2) / zoom
        camY = wy - (py - bottom - height / 2) / zoom
    }

    fun pan(dxPixels: Float, dyPixels: Float) { follow = false; camX -= dxPixels / zoom; camY -= dyPixels / zoom }

    private fun ensure(n: Int) {
        if (sx.size >= n) return
        val cap = maxOf(n, sx.size * 2)
        sx = FloatArray(cap); sy = FloatArray(cap); kind = IntArray(cap); bodyOf = IntArray(cap); command = FloatArray(cap); dim = BooleanArray(cap)
        size = FloatArray(cap)
    }

    private fun shade(base: Color, asleep: Boolean, alpha: Float): Color {
        scratch.set(base)
        if (asleep) scratch.mul(0.5f, 0.5f, 0.55f, 1f)
        scratch.a = alpha
        return scratch
    }

    private fun cellColor(i: Int, alpha: Float): Color {
        val k = kind[i]
        val base = when {
            k == 1 -> Palette.BONE
            k >= 2 -> {
                val c = command[i]
                val group = Palette.MUSCLE[(k - 2) % Palette.MUSCLE.size]
                scratch.set(group)
                // Команда 0.5 — сокращена до предела, 1 — покой, 1.5 — расслаблена.
                if (c < 1f) scratch.lerp(Palette.MUSCLE_TIGHT, ((1f - c) * 2f).coerceIn(0f, 1f) * 0.75f)
                else if (c > 1f) scratch.lerp(Palette.MUSCLE_LOOSE, ((c - 1f) * 2f).coerceIn(0f, 1f) * 0.75f)
                if (dim[i]) scratch.mul(0.5f, 0.5f, 0.55f, 1f)
                scratch.a = alpha
                return scratch
            }
            else -> Palette.SOFT[Math.floorMod(bodyOf[i], Palette.SOFT.size)]
        }
        return shade(base, dim[i], alpha)
    }

    /** Рисует мир прогона. Вызывается между beginFrame и панелями: панели закрывают края сцены. */
    fun draw(c: PrimitiveCanvas, run: StandRun, mouseWorld: DoubleArray?) {
        val w = run.w
        val st = w.world.state
        val n = st.n
        ensure(n)
        val k = PHYSICS_PER_GAME
        val contacts = st.contacts
        for (i in 0 until n) {
            sx[i] = screenX(st.px[i] / k); sy[i] = screenY(st.py[i] / k)
            kind[i] = -1; bodyOf[i] = -1; command[i] = 1f
            val o = st.organismOf[i]
            dim[i] = o >= 0 && st.sleep.isAsleep(o)
            // Касания заводятся на первом шаге мира и растут вместе с ним: частицы, которой у них ещё нет, касается только её радиус.
            val touch = if (contacts == null) 0.0 else try { contacts.contactRadiusOf(i) } catch (e: IndexOutOfBoundsException) { 0.0 }
            size[i] = (maxOf(st.radius[i] * LONE_SCALE, touch) / k * zoom).toFloat()
        }
        for (b in w.bodies) {
            val first = b.first
            for (cell in 0 until b.count) {
                val p = first + cell
                bodyOf[p] = b.index
                val group = b.shape.muscle[cell]
                kind[p] = when { st.boneOf[p] >= 0 -> 1; group >= 0 -> 2 + group; else -> 0 }
                if (group >= 0) command[p] = b.command[group]
            }
        }
        val contact = (CONTACT_DISTANCE / 2 * zoom).toFloat()

        c.begin(true)
        // --- рельеф: клетки карты и частицы кромки ---
        val cell = (TERRAIN_STEP * zoom).toFloat()
        for (t in w.terrains) {
            c.color = Palette.BULK
            for (iy in 0 until t.rows) for (ix in 0 until t.cols) {
                if (!t.solid[iy * t.cols + ix]) continue
                val x = screenX(t.centerX(ix)) - cell / 2; val y = screenY(t.centerY(iy)) - cell / 2
                if (x > left + width || x + cell < left || y > bottom + height || y + cell < bottom) continue
                c.rect(x, y, cell, cell)
            }
        }
        c.color = Palette.ROCK
        val segments = if (contact > 14f) 20 else if (contact > 5f) 12 else 6
        for (id in w.fixed) {
            val p = w.world.freeParticle(id)
            if (visible(p, contact)) c.circle(sx[p], sy[p], maxOf(1.2f, size[p]), segments)
        }

        // --- превью: взрослое тело, каким должно вырасти растущее ---
        if (showPreview) for (b in w.bodies) {
            if (b.growth == null) continue
            val first = b.first
            val def = b.def
            if (zoom >= 7.0) {
                c.color = GHOST_LINK
                for (e in 0 until def.linkCount) {
                    val i = def.linkA[e]; val j = def.linkB[e]
                    if (!st.cellUnborn[first + i] && !st.cellUnborn[first + j]) continue
                    c.rectLine(screenX(def.x[i] / k), screenY(def.y[i] / k), screenX(def.x[j] / k), screenY(def.y[j] / k), 1f)
                }
            }
            for (cellIndex in 0 until b.count) {
                if (!st.cellUnborn[first + cellIndex]) continue
                val gx = screenX(def.x[cellIndex] / k); val gy = screenY(def.y[cellIndex] / k)
                if (gx < left - 20 || gx > left + width + 20 || gy < bottom - 20 || gy > bottom + height + 20) continue
                c.color = if (b.shape.bone[cellIndex]) GHOST_BONE else if (b.shape.muscle[cellIndex] >= 0) GHOST_MUSCLE else GHOST
                c.circle(gx, gy, maxOf(1.2f, (def.radius[cellIndex] * LONE_SCALE / k * zoom * 0.82).toFloat()), if (segments > 8) 10 else 6)
            }
        }

        // --- ткань ---
        val body = st.body
        val alive = st.baked.triAlive
        for (t in 0 until body.triCount) {
            if (!alive[t]) continue
            val a = body.triA[t]; val b = body.triB[t]; val d = body.triC[t]
            if (st.cellDead[a] || st.cellDead[b] || st.cellDead[d] || st.cellUnborn[a] || st.cellUnborn[b] || st.cellUnborn[d]) continue
            // Цвет треугольника — по «старшей» клетке: кость только если костные все три, мышца — если есть хоть одна.
            val lead = when {
                kind[a] >= 2 -> a; kind[b] >= 2 -> b; kind[d] >= 2 -> d
                kind[a] == 1 && kind[b] == 1 && kind[d] == 1 -> a
                kind[a] != 1 -> a; kind[b] != 1 -> b; else -> d
            }
            val inverted = (sx[b] - sx[a]) * (sy[d] - sy[a]) - (sy[b] - sy[a]) * (sx[d] - sx[a]) <= 0f
            c.color = if (inverted) shade(Palette.FAIL, false, 0.9f) else cellColor(lead, 0.62f)
            c.triangle(sx[a], sy[a], sx[b], sy[b], sx[d], sy[d])
        }

        // --- связи ---
        if (showLinks && zoom >= 7.0) {
            c.color = Palette.LINK
            val thick = (zoom * 0.035).toFloat().coerceIn(1f, 3f)
            for (b in w.bodies) {
                val first = b.first
                val def = b.def
                for (e in 0 until def.linkCount) {
                    val i = first + def.linkA[e]; val j = first + def.linkB[e]
                    if (st.cellDead[i] || st.cellDead[j] || st.cellUnborn[i] || st.cellUnborn[j]) continue
                    val edge = st.baked.edgeOf(i, j)
                    if (edge < 0 || st.baked.edgeDead[edge]) continue
                    c.rectLine(sx[i], sy[i], sx[j], sy[j], thick)
                }
            }
        }

        // --- клетки тел и свободные частицы ---
        if (showCells) {
            for (b in w.bodies) {
                val first = b.first
                for (cellIndex in 0 until b.count) {
                    val p = first + cellIndex
                    if (st.cellDead[p] || st.cellUnborn[p] || !visible(p, contact)) continue
                    c.color = cellColor(p, 0.95f)
                    c.circle(sx[p], sy[p], maxOf(1.2f, size[p] * 0.82f), segments)
                    // Присоска держится за дно: тёмная сердцевина, тем крупнее, чем сильнее хватка.
                    val grip = st.cellGrip[p]
                    if (grip > 0f) { c.color = GRIP; c.circle(sx[p], sy[p], maxOf(1f, size[p] * 0.5f * grip), segments) }
                }
            }
        }
        for (id in w.balls) {
            val p = w.world.freeParticle(id)
            if (st.cellDead[p] || !visible(p, contact)) continue
            c.color = shade(Palette.BALL, dim[p], 1f)
            c.circle(sx[p], sy[p], maxOf(1.5f, size[p]), segments)
        }

        // --- мышь сценария или человека ---
        val grabbed = w.grabbedBody
        if (grabbed != null && w.grabbedCell >= 0) {
            val p = grabbed.particle(w.grabbedCell)
            val mx = screenX(w.mouseX); val my = screenY(w.mouseY)
            c.color = Palette.MOUSE
            c.rectLine(sx[p], sy[p], mx, my, 2f)
            c.circle(mx, my, 5f, 12)
        } else if (mouseWorld != null) {
            c.color = shade(Palette.MOUSE, false, 0.35f)
            c.circle(screenX(mouseWorld[0]), screenY(mouseWorld[1]), 3f, 10)
        }
        c.end()

        // --- отметки нарушений: кольца цвета правила, гаснут за полторы секунды игры ---
        if (marks.isNotEmpty()) {
            c.begin(false)
            val now = run.tick
            for (m in marks) {
                val age = now - m.tick
                if (age > MARK_TICKS) continue
                val x = screenX(m.x); val y = screenY(m.y)
                if (x < left - 30 || x > left + width + 30 || y < bottom - 30 || y > bottom + height + 30) continue
                val r = maxOf(7f, (0.55 * zoom).toFloat())
                c.color = shade(markColor(m.rule), false, 1f - 0.7f * age / MARK_TICKS)
                c.circle(x, y, r, 20); c.circle(x, y, r + 1.2f, 20); c.circle(x, y, r + 2.4f, 20)
            }
            c.end()
        }
    }

    /**
     * Тень: та же сцена, посчитанная другим решателем, поверх основной — белым контуром (связи тел
     * и кружки свободных частиц). Рисуется после [draw]: где тень легла точно на тело, решатели
     * сошлись; где отъехала — разошлись.
     */
    fun drawShadow(c: PrimitiveCanvas, run: StandRun) {
        val w = run.w
        val st = w.world.state
        val k = PHYSICS_PER_GAME
        val thick = (zoom * 0.05).toFloat().coerceIn(1.2f, 3f)
        c.begin(true)
        c.color = SHADOW
        for (b in w.bodies) {
            val first = b.first
            val def = b.def
            for (e in 0 until def.linkCount) {
                val i = first + def.linkA[e]; val j = first + def.linkB[e]
                if (st.cellDead[i] || st.cellDead[j] || st.cellUnborn[i] || st.cellUnborn[j]) continue
                val edge = st.baked.edgeOf(i, j)
                if (edge < 0 || st.baked.edgeDead[edge]) continue
                val x1 = screenX(st.px[i] / k); val y1 = screenY(st.py[i] / k)
                if (x1 < left - 50 || x1 > left + width + 50 || y1 < bottom - 50 || y1 > bottom + height + 50) continue
                c.rectLine(x1, y1, screenX(st.px[j] / k), screenY(st.py[j] / k), thick)
            }
            // Тело из одной клетки или без связей — хотя бы точкой.
            if (def.linkCount == 0) for (cell in 0 until b.count) {
                val p = first + cell
                if (st.cellDead[p] || st.cellUnborn[p]) continue
                c.circle(screenX(st.px[p] / k), screenY(st.py[p] / k), 2.5f, 8)
            }
        }
        c.end()
        if (w.balls.isNotEmpty()) {
            c.begin(false)
            c.color = SHADOW
            for (id in w.balls) {
                val p = w.world.freeParticle(id)
                if (st.cellDead[p]) continue
                val r = maxOf(2f, (st.radius[p] * LONE_SCALE / k * zoom).toFloat())
                val x = screenX(st.px[p] / k); val y = screenY(st.py[p] / k)
                c.circle(x, y, r, 14); c.circle(x, y, r + 1f, 14)
            }
            c.end()
        }
    }

    private fun visible(p: Int, r: Float): Boolean =
        sx[p] > left - r && sx[p] < left + width + r && sy[p] > bottom - r && sy[p] < bottom + height + r

    /** Забрать отметки монитора за только что посчитанный тик. */
    fun collect(run: StandRun) {
        val tick = run.tick
        for (m in run.monitor.marks) marks.add(StickyMark(m.rule, m.x, m.y, tick))
        if (marks.size > 3000) marks.subList(0, marks.size - 2000).clear()
    }

    /** Ближайшая живая клетка тела к точке игры не дальше [reach]: тело и номер клетки, или null. */
    fun pick(w: StandWorld, gx: Double, gy: Double, reach: Double): Pair<Int, Int>? {
        val st = w.world.state
        var best: Pair<Int, Int>? = null
        var bestD = reach * reach
        for (b in w.bodies) {
            val first = b.first
            for (cell in 0 until b.count) {
                val p = first + cell
                if (st.cellDead[p] || st.cellUnborn[p]) continue
                val dx = st.px[p] / PHYSICS_PER_GAME - gx; val dy = st.py[p] / PHYSICS_PER_GAME - gy
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = b.index to cell }
            }
        }
        return best
    }

    companion object {
        const val MARK_TICKS = 45

        fun markColor(rule: Rule): Color = when (rule) {
            Rule.FOREIGN, Rule.CROSS -> MARK_FOREIGN
            Rule.SELF -> MARK_SELF
            Rule.TERRAIN -> MARK_TERRAIN
            Rule.DEATHS -> MARK_DEATH
            Rule.INVERSION -> MARK_INVERSION
            Rule.BONES -> MARK_BONE
            Rule.SHAPE -> MARK_SHAPE
            Rule.STILL -> MARK_STILL
            Rule.JITTER -> MARK_JITTER
            else -> Palette.WARN
        }

        val MARK_FOREIGN: Color = Color.valueOf("FF4040FF")
        val MARK_SELF: Color = Color.valueOf("FF9A3CFF")
        val MARK_TERRAIN: Color = Color.valueOf("FF5CE1FF")
        val MARK_DEATH: Color = Color.valueOf("FFFFFFFF")
        val MARK_INVERSION: Color = Color.valueOf("FFE14DFF")
        val MARK_BONE: Color = Color.valueOf("4DE1FFFF")
        val MARK_SHAPE: Color = Color.valueOf("B58CFFFF")
        val MARK_STILL: Color = Color.valueOf("5C8CFFFF")
        val MARK_JITTER: Color = Color.valueOf("7CFF6BFF")
        val GRIP: Color = Color.valueOf("1B1030FF")
        /** Тень — прогон той же сцены другим решателем. */
        val SHADOW: Color = Color.valueOf("FFFFFFC0")
        private val GHOST: Color = Color.valueOf("9FB4D030")
        private val GHOST_BONE: Color = Color.valueOf("EDE6D240")
        private val GHOST_MUSCLE: Color = Color.valueOf("D9605A38")
        private val GHOST_LINK: Color = Color.valueOf("9FB4D020")
        /** Радиус касания частицы без связей к её радиусу — как CONTACT_SCALE решателя. */
        private const val LONE_SCALE = 0.6
    }
}
