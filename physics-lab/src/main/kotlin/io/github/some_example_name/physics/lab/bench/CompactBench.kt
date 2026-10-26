package io.github.some_example_name.physics.lab.bench

import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.physics.WorldScale
import io.github.some_example_name.physics.lab.io.SceneFile
import io.github.some_example_name.physics.lab.session.DemoSettings
import java.util.Locale

/**
 * ЦЕНА УПЛОТНЕНИЯ МИРА (PhysicsWorld.compact) на прогретом коде. Мир собирается из тел
 * сцены; затем по кругу: удалить тело, уплотнить, дописать то же тело обратно — размер
 * мира держится. Печатается время уплотнения (медиана и худшее) и время дописывания.
 *
 * Запуск: CompactBench <сцена.scene> [кругов]
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val bodies = SceneFile.loadBodies(args.getOrElse(0) { "docs/physics/scenes/showcase-30k.scene" })
    val rounds = args.getOrNull(1)?.toIntOrNull() ?: 30
    val world = PhysicsWorld(PhysicsConfig(DemoSettings.fromEnvironment().snapshot()), scale = WorldScale.of(bodies[0]))
    world.state.quiet = true
    val ids = IntArray(bodies.size) { world.addBody(bodies[it]) }
    repeat(5) { world.step() }
    val compactNs = LongArray(rounds); val appendNs = LongArray(rounds)
    val parts = LongArray(8)
    for (r in 0 until rounds) {
        // Тело из середины мира: сдвигается всё, что после него.
        val k = 1 + (r * 7919) % (bodies.size - 1)
        world.removeBody(ids[k])
        val t0 = System.nanoTime()
        if (world.removedParticles > 0) world.compact()
        val t1 = System.nanoTime()
        ids[k] = world.addBody(bodies[k])
        val t2 = System.nanoTime()
        compactNs[r] = t1 - t0; appendNs[r] = t2 - t1
        if (r >= rounds / 3) for (k in 0..7) parts[k] += world.state.dbgCompactNs[k]
        world.step()
    }
    fun ms(a: LongArray, q: Double) = a.sorted()[((a.size - 1) * q).toInt()] / 1e6
    val warm = compactNs.copyOfRange(rounds / 3, rounds); val warmA = appendNs.copyOfRange(rounds / 3, rounds)
    println("COMPACT_BENCH particles=${world.particleCount} bodies=${bodies.size}: уплотнение медиана %.1f мс, худшее %.1f мс; дописывание медиана %.1f мс"
        .format(ms(warm, 0.5), ms(warm, 1.0), ms(warmA, 0.5)))
    val names = listOf("разрывы", "карты и описание", "массивы и списки", "топология", "контур и поверхность", "сон и пределы", "контакты", "решатель")
    println("COMPACT_BENCH части (среднее): " + names.indices.joinToString(", ") { "%s %.1f".format(names[it], parts[it] / 1e6 / warm.size) } + " мс")
}
