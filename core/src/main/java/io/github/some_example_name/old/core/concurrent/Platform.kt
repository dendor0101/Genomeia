package io.github.some_example_name.old.core.concurrent


// Глобальная точка доступа
object Platform {
    lateinit var concurrent: ConcurrentFactory
    lateinit var simulationFactory: SimulationSystemFactory
}
