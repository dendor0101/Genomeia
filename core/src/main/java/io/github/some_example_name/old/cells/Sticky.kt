package io.github.some_example_name.old.cells

import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.utils.pinkColors

/**
 * Липучка: при импульсе больше нуля прилипает к тому, чего коснулась, — к клетке чужого
 * организма, еде или стенке. Держит временная связь мира физики (PhysicsWorld.addJoint):
 * касание находится здесь, в параллельной стадии, а связь заводит мост однопоточно по команде
 * STICK (WorldPhysics.requestStick). Сила — импульс, при нуле связь отпускается
 * (WorldPhysics.syncSticks). Раньше липучка тянула пару пружиной прежней физики прямо в
 * скоростях и только пока частицы перекрывались.
 */
class Sticky(cellTypeId: Int) : Cell(
    key = "sticky",
    defaultColor = pinkColors[3],
    cellTypeId = cellTypeId,
    textureName = "sticky.png",
    isNeural = true,
    effectOnContact = true
) {

    override fun onContact(
        cellIndex: Int,
        particleIndexCollided: Int,
        distance: Float,
        threadId: Int
    ) {
        if (cellEntity.neuronImpulseOutput[cellIndex] <= 0f) return
        // Своё тело держат его связи: липнуть к себе незачем.
        if (particleEntity.isCell[particleIndexCollided] &&
            cellEntity.organIndex[particleEntity.holderEntityIndex[particleIndexCollided]] == cellEntity.organIndex[cellIndex]) return
        worldCommandsManager.worldCommandBuffer[threadId].push(
            WorldCommandType.STICK, cellIndex, cellEntity.getGeneration(cellIndex), particleIndexCollided
        )
    }

}
