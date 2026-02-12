package io.github.some_example_name.old.shader_instancing

class ShaderBuffer(maxAmountOfCells: Int) {
    //если 100к клеток, то это весит 12мб оперативки

    //Cells
    var x = FloatArray(maxAmountOfCells)//Возможно можно также передать через int
    var y = FloatArray(maxAmountOfCells)//Возможно можно также передать через int
    var colorRGBA = IntArray(maxAmountOfCells) //256*256*256*256 - rgba
//    var angle_cellType_radius = IntArray(maxAmountOfCells) //(angle - (256 * 8)) * (cellType * 32) * (radius-256*256) // точностью радиуса можно пожертвовать в пользу, например стейта с 4 значениями
//    //На определенном удалении, можно не использлвать или если больше 100_000
//    var ax_ay_energy_cellStrength_collisionCount = IntArray(maxAmountOfCells) //Int (ax-256)*(ay-256)*(energy-256)*(cellStrength-16)*(collisionCount-16)
//    var collisionId = IntArray(maxAmountOfCells * 10)

}
//
//fun packRGBA(r: Int, g: Int, b: Int, a: Int): Int {
//
//}
