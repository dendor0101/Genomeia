package io.github.some_example_name.physics.lab.stands

/**
 * КАТАЛОГ СЦЕН. Семейства идут от простого к сложному; порядок — тот, в котором их удобно
 * смотреть в окне. Номер сцены постоянный: по нему её зовут из командной строки, по нему же
 * сравнивают отчёты разных прогонов.
 *
 * Семейства 1–14 — фигуры на решётке редактора; каждая их сцена идёт во всех вариациях длин
 * связей и радиусов клеток (Variation). Семейства 15–20 — тела настоящих геномов, со своими
 * длинами и радиусами.
 */
object Catalog {
    private val scenarios: List<Scenario> by lazy { build() }

    fun all(): List<Scenario> = scenarios

    private fun build(): List<Scenario> {
        val list = ArrayList<Scenario>()
        list += primitiveFamily()
        list += complexFamily()
        list += boneFamily()
        list += terrainFamily()
        list += particleFamily()
        list += muscleFamily()
        list += leverFamily()
        list += growthFamily()
        list += sleepFamily()
        list += scaleFamily()
        list += ramFamily()
        list += hydroFamily()
        list += crowdFamily()
        list += stretchFamily()
        list += extraFamilies()
        list += io.github.some_example_name.physics.lab.stands.perf.perfFamily()
        list += genomeHitFamily()
        list += genomeTerrainFamily()
        list += genomeMuscleFamily()
        list += genomeGrowthFamily()
        list += genomeStageFamily()
        list += genomeZooFamily()
        list += freeFamily()
        list += gridFamily()
        list += pressFamily()
        list += logicFamily()
        list += oddFamily()
        list += hugeFamily()
        list += genomeMotionFamily()
        return list
    }
}
