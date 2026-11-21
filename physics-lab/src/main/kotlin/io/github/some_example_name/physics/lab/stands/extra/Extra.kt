package io.github.some_example_name.physics.lab.stands

/**
 * ДОПОЛНИТЕЛЬНЫЕ СЕМЕЙСТВА: сцены, придуманные сверх основного перечня (файлы CatalogExtra*.kt
 * этой папки). Каждое семейство — отдельная функция; здесь они только собираются в список.
 *
 * Эти шесть семейств 15.11 сочинили модели-помощники по одной теме на каждую, по памятке
 * docs/physics/stands-author-guide.md: задача была — придумать сцены, до которых основной
 * каталог не додумался. Сцены собраны, запущены и сверены с проверкой расстановки, но глазами
 * просмотрена лишь малая часть: пороги в их ожиданиях — оценки, а не замеры. Сцену, которая
 * поставлена неверно, помечают в окне (клавиша M) и правят или убирают здесь.
 */
internal fun extraFamilies(): List<Scenario> {
    val list = ArrayList<Scenario>()
    list += extraShapesFamily()
    list += extraMusclesFamily()
    list += extraGrowthFamily()
    list += extraParticlesFamily()
    list += extraEnduranceFamily()
    list += extraComboFamily()
    return list
}
