package io.github.some_example_name.old.physics.bridge

import io.github.some_example_name.old.systems.genomics.genome.BakedLayout
import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.withTempLinks

/**
 * ТЕЛО БИБЛИОТЕКИ ИЗ ЗАПЕЧЁННОЙ РАСКЛАДКИ ГЕНОМА. Частица k тела — слот k раскладки (то есть
 * `cellIndex = cellArenaBase + k` игры), связь k — слот связи k: события библиотеки (разрыв,
 * гибель) адресуются слотами без поиска.
 *
 * Поза покоя — BakedLayout.restX/restY/restRadius в единицах мира физики (PHYSICS_PER_GAME),
 * сдвинутая так, что клетка [anchorSlot] стоит в ([anchorX], [anchorY]) единиц игры: тело
 * растёт из зиготы в точке подсадки, рождённые клетки встают по позе вокруг неё.
 *
 * Треугольники — тройки слотов раскладки; удвоенная площадь считается заново по позе в
 * единицах физики (а не BakedLayout.triangleRestArea2 · K²), чтобы точно совпасть с длинами
 * покоя, которые библиотека выведет из той же позы. Порядок вершин раскладка уже развернула
 * до положительной площади; тройка, выродившаяся в ноль при округлении, пропускается (так
 * же отбрасывает их стенд, BodyFile.build).
 *
 * Мышц-кластеров нет: мышца у каждой клетки (решение 26.10, PhysicsWorld.setCellScale).
 * Кости — связные куски костных клеток (BakedLayout.restBone), обход в ширину по связям
 * раскладки, как у стенда: несоединённые кости — разные жёсткие тела.
 *
 * Цена — O(клеток + связей) с выделениями, раз на подсадку организма.
 */
fun bodyDefinitionOf(layout: BakedLayout, anchorSlot: Int, anchorX: Float, anchorY: Float): BodyDefinition {
    require(layout.hasRestPose) { "у раскладки нет позы покоя (BakedLayout.restX) — тело не из чего завести" }
    val n = layout.cellsInLayout
    val k = PHYSICS_PER_GAME
    val ox = anchorX - layout.restX[anchorSlot]
    val oy = anchorY - layout.restY[anchorSlot]
    val x = FloatArray(n) { ((layout.restX[it] + ox) * k).toFloat() }
    val y = FloatArray(n) { ((layout.restY[it] + oy) * k).toFloat() }
    val r = FloatArray(n) { (layout.restRadius[it] * k).toFloat() }
    val bone = BooleanArray(n) { layout.restBone[it] }

    val links = layout.linksInLayout
    val allA = IntArray(links)
    val allB = IntArray(links)
    val pairs = layout.linkPairsInSlotOrder
    for (s in 0 until links) {
        allA[s] = layout.slotByCellGenomeId.get(pairs[2 * s])
        allB[s] = layout.slotByCellGenomeId.get(pairs[2 * s + 1])
        check(allA[s] >= 0 && allB[s] >= 0) { "связь $s раскладки ссылается на клетку вне раскладки" }
    }
    // Связи поперёк граней постоянными физика не получает (BakedLayout.crossingLinks): голая связь
    // посреди ткани для неё край (контур, среда, касания). Опыт 03.11, вариант А заново: после того
    // как дорастающая связь перестала рваться от падающего предела (isGrowingLink). Но растущему телу
    // они нужны, пока на них не легли клетки: они идут в конец связей временными (withTempLinks,
    // physics/topology/TempLinks.kt, находка 32) — у взрослого тела их нет вовсе (BodyDefinition.forBorn).
    val skip = BooleanArray(links)
    for (s in layout.crossingLinks) skip[s] = true
    val crossA = IntArray(layout.crossingLinks.size) { allA[layout.crossingLinks[it]] }
    val crossB = IntArray(layout.crossingLinks.size) { allB[layout.crossingLinks[it]] }
    val kept = links - layout.crossingLinks.size
    val linkA = IntArray(kept)
    val linkB = IntArray(kept)
    var m = 0
    for (s in 0 until links) {
        if (skip[s]) continue
        linkA[m] = allA[s]; linkB[m] = allB[s]; m++
    }

    val tris = layout.trianglesInLayout
    val slots = layout.triangleSlotsArray
    val ta = IntArray(tris); val tb = IntArray(tris); val tc = IntArray(tris)
    val area = FloatArray(tris)
    var t = 0
    for (i in 0 until tris) {
        val a = slots[3 * i]; val b = slots[3 * i + 1]; val c = slots[3 * i + 2]
        val a2 = (x[b] - x[a]) * (y[c] - y[a]) - (y[b] - y[a]) * (x[c] - x[a])
        if (a2 < 1e-12f) continue
        ta[t] = a; tb[t] = b; tc[t] = c; area[t] = a2; t++
    }

    return BodyDefinition(
        x = x, y = y, radius = r, isBone = bone, isMuscle = BooleanArray(n),
        linkA = linkA, linkB = linkB,
        triA = ta.copyOf(t), triB = tb.copyOf(t), triC = tc.copyOf(t), triRestArea2 = area.copyOf(t),
        // Куски костей — по всем связям раскладки: связь поперёк граней тоже скрепляет кость.
        boneClusters = boneClusters(n, allA, allB, bone),
        muscleClusters = emptyArray(),
        scale = k.toFloat()
    ).withTempLinks(crossA, crossB)
}

/** Связные компоненты костных клеток по связям; порядок — по первому слоту компоненты. */
private fun boneClusters(n: Int, linkA: IntArray, linkB: IntArray, bone: BooleanArray): Array<IntArray> {
    if (bone.none { it }) return emptyArray()
    // Смежность костных клеток плоскими массивами (CSR): связей втрое больше клеток.
    val degree = IntArray(n + 1)
    for (s in linkA.indices) if (bone[linkA[s]] && bone[linkB[s]]) { degree[linkA[s] + 1]++; degree[linkB[s] + 1]++ }
    for (v in 0 until n) degree[v + 1] += degree[v]
    val adjacent = IntArray(degree[n])
    val fill = degree.copyOf()
    for (s in linkA.indices) {
        val a = linkA[s]; val b = linkB[s]
        if (!bone[a] || !bone[b]) continue
        adjacent[fill[a]++] = b; adjacent[fill[b]++] = a
    }
    val seen = BooleanArray(n)
    val queue = IntArray(n)
    val result = ArrayList<IntArray>()
    for (start in 0 until n) {
        if (!bone[start] || seen[start]) continue
        var head = 0; var tail = 0
        queue[tail++] = start; seen[start] = true
        while (head < tail) {
            val v = queue[head++]
            for (e in degree[v] until degree[v + 1]) {
                val u = adjacent[e]
                if (!seen[u]) { seen[u] = true; queue[tail++] = u }
            }
        }
        result.add(queue.copyOf(tail))
    }
    return result.toTypedArray()
}
