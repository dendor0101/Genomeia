package io.github.some_example_name.attempts.game.physics

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.attempts.game.gameabstraction.Cell
import io.github.some_example_name.attempts.game.gameabstraction.entity.ParticleType
import io.github.some_example_name.attempts.game.main.Genomeia.Companion.INIT_PARTICLE_RADIUS
import io.github.some_example_name.attempts.game.main.Genomeia.Companion.MAX_REPULSION_RADIUS

class Particle(var x: Float, var y: Float, val type: ParticleType) {
    var vx = 0f
    var vy = 0f
    var repulsionRadius = if (type == ParticleType.WALL) MAX_REPULSION_RADIUS else INIT_PARTICLE_RADIUS * 2
    var updatedBy = mutableListOf<Int>()
    var isOld = false
    var color = if (type == ParticleType.WALL) soilColors.random() else leafColors.random()
    var colorCore = if (type == ParticleType.WALL) soilColors.random() else leafColors.random()
    var cell = Cell(
        generation = 0,
        genomeId = "file"
    )
    var genomeId = 0
    var linkedParticleId = listOf<Int>()

    var tree = listOf<Boolean>()
    //TODO Все, что не нужно каждый кадр, можно сохранять прям в бд (подумать об этом)
}

val soilColors = listOf(
    Color(0.545f, 0.271f, 0.075f, 1.0f), // 0x8B4513FF (коричневый)
    Color(0.957f, 0.643f, 0.376f, 1.0f), // 0xF4A460FF (теплый бежевый)
    Color(0.396f, 0.263f, 0.129f, 1.0f), // 0x654321FF (темно-коричневый)
    Color(0.545f, 0.451f, 0.333f, 1.0f), // 0x6B8E23FF (оливково-зеленый)
    Color(0.886f, 0.447f, 0.357f, 1.0f), // 0xE2725BFF (терракотовый)
    Color(0.545f, 0.451f, 0.333f, 1.0f)  // 0x8B7355FF (серо-коричневый)
)

val leafColors = listOf(
    Color(0.133f, 0.545f, 0.133f, 1.0f), // 0x228B22FF (лесной зеленый)
    Color(0.180f, 0.545f, 0.341f, 1.0f), // 0x2E8B57FF (морской зеленый)
    Color(0.420f, 0.557f, 0.137f, 1.0f), // 0x6B8E23FF (оливково-зеленый)
    Color(0.000f, 0.502f, 0.000f, 1.0f), // 0x008000FF (зеленый)
    Color(0.196f, 0.804f, 0.196f, 1.0f), // 0x32CD32FF (лаймовый зеленый)
    Color(0.125f, 0.698f, 0.667f, 1.0f), // 0x20B2AAFF (светло-морской зеленый)
    Color(0.678f, 1.000f, 0.184f, 1.0f)  // 0xADFF2FFF (зеленый шартрез)
)

val blueColors = listOf(
    Color(0.000f, 0.000f, 1.000f, 1.0f),  // 0x0000FFFF (чистый синий)
    Color(0.118f, 0.565f, 1.000f, 1.0f),  // 0x1E90FFFF (ярко-синий)
    Color(0.000f, 0.749f, 1.000f, 1.0f),  // 0x00BFFFFF (глубокий небесно-синий)
    Color(0.255f, 0.412f, 0.882f, 1.0f),  // 0x4169E1FF (королевский синий)
    Color(0.000f, 0.502f, 0.502f, 1.0f),  // 0x008080FF (темный бирюзовый)
    Color(0.294f, 0.000f, 0.510f, 1.0f),  // 0x4B0082FF (индиго)
    Color(0.000f, 0.275f, 0.545f, 1.0f)   // 0x00468BFF (темно-синий)
)

val redColors = listOf(
    Color(1.000f, 0.000f, 0.000f, 1.0f),  // 0xFF0000FF (чистый красный)
    Color(0.863f, 0.078f, 0.235f, 1.0f),  // 0xDC143CFF (малиновый)
    Color(0.698f, 0.133f, 0.133f, 1.0f),  // 0xB22222FF (огненно-красный)
    Color(0.804f, 0.000f, 0.000f, 1.0f),  // 0xCD0000FF (тёмно-красный)
    Color(0.545f, 0.000f, 0.000f, 1.0f),  // 0x8B0000FF (бордовый)
    Color(0.502f, 0.000f, 0.000f, 1.0f),  // 0x800000FF (маррон)
    Color(0.647f, 0.165f, 0.165f, 1.0f)   // 0xA52A2AFF (коричнево-красный)
)

val pinkColors = listOf(
    Color(1.000f, 0.753f, 0.796f, 1.0f),  // 0xFFC0CBFF (розовый)
    Color(1.000f, 0.412f, 0.706f, 1.0f),  // 0xFF69B4FF (ярко-розовый)
    Color(0.980f, 0.502f, 0.745f, 1.0f),  // 0xFA80BDFF (средний розовый)
    Color(0.941f, 0.502f, 0.502f, 1.0f),  // 0xF08080FF (кораллово-розовый)
    Color(0.867f, 0.627f, 0.867f, 1.0f),  // 0xDD9FDFFF (лавандово-розовый)
    Color(0.878f, 0.400f, 0.576f, 1.0f),  // 0xE06693FF (тёмно-розовый)
    Color(0.780f, 0.082f, 0.522f, 1.0f)   // 0xC71485FF (фуксия)
)

val whiteColors = listOf(
    Color(1.000f, 1.000f, 1.000f, 1.0f),  // 0xFFFFFFFF (чистый белый)
    Color(0.980f, 0.980f, 0.980f, 1.0f),  // 0xFAFAFAFF (белый дым)
    Color(0.961f, 0.961f, 0.961f, 1.0f),  // 0xF5F5F5FF (белый дымчатый)
    Color(0.941f, 0.941f, 0.941f, 1.0f),  // 0xF0F0F0FF (белый античный)
    Color(0.922f, 0.922f, 0.922f, 1.0f),  // 0xEBEBEBFF (белый платиновый)
    Color(0.902f, 0.902f, 0.902f, 1.0f),  // 0xE6E6E6FF (белый жемчужный)
    Color(0.878f, 0.878f, 0.878f, 1.0f)   // 0xE0E0E0FF (белый сероватый)
)

val yellowColors = listOf(
    Color(1.000f, 0.843f, 0.000f, 1.0f),  // 0xFFD700FF (золотой)
    Color(1.000f, 0.894f, 0.482f, 1.0f),  // 0xFFE47AFF (светло-золотой)
    Color(1.000f, 0.937f, 0.000f, 1.0f),  // 0xFFEF00FF (ярко-желтый)
    Color(0.980f, 0.941f, 0.471f, 1.0f),  // 0xFAF078FF (пастельно-желтый)
    Color(0.992f, 0.922f, 0.000f, 1.0f),  // 0xFDEB00FF (лимонный)
    Color(1.000f, 0.973f, 0.863f, 1.0f),  // 0xFFF8DCFF (кукурузный)
    Color(0.941f, 0.902f, 0.549f, 1.0f)   // 0xF0E68CFF (хаки)
)

val purpleColors = listOf(
    Color(0.580f, 0.000f, 0.827f, 1.0f), // 0x9400D3FF (тёмно-фиолетовый)
    Color(0.502f, 0.000f, 0.502f, 1.0f), // 0x800080FF (пурпурный)
    Color(0.729f, 0.333f, 0.827f, 1.0f), // 0xBA55D3FF (средний фиолетовый)
    Color(0.600f, 0.196f, 0.800f, 1.0f), // 0x9932CCFF (тёмный орхидей)
    Color(0.859f, 0.439f, 0.576f, 1.0f), // 0xDB7093FF (бледно-фиолетовый)
    Color(0.545f, 0.000f, 0.545f, 1.0f), // 0x8B008BFF (тёмный пурпурный)
    Color(0.933f, 0.510f, 0.933f, 1.0f)  // 0xEE82EEFF (фиолетовый)
)

val orangeColors = listOf(
    Color(1.000f, 0.647f, 0.000f, 1.0f), // 0xFFA500FF (оранжевый)
    Color(1.000f, 0.549f, 0.000f, 1.0f), // 0xFF8C00FF (тёмно-оранжевый)
    Color(1.000f, 0.400f, 0.000f, 1.0f), // 0xFF6600FF (ярко-оранжевый)
    Color(1.000f, 0.270f, 0.000f, 1.0f), // 0xFF4500FF (красно-оранжевый)
    Color(1.000f, 0.800f, 0.400f, 1.0f), // 0xFFCC66FF (светло-оранжевый)
    Color(1.000f, 0.600f, 0.200f, 1.0f), // 0xFF9933FF (средний оранжевый)
    Color(0.980f, 0.502f, 0.447f, 1.0f)  // 0xFA8072FF (коралловый)
)

val skyBlueColors = listOf(
    Color(0.000f, 0.749f, 1.000f, 1.0f), // 0x00BFFFFF (голубой)
    Color(0.529f, 0.808f, 0.922f, 1.0f), // 0x87CEEBFF (небесно-голубой)
    Color(0.678f, 0.847f, 0.902f, 1.0f), // 0xADD8E6FF (светло-голубой)
    Color(0.275f, 0.510f, 0.706f, 1.0f), // 0x4682B4FF (стальной голубой)
    Color(0.000f, 0.498f, 1.000f, 1.0f), // 0x007FFFFF (ярко-голубой)
    Color(0.690f, 0.878f, 0.902f, 1.0f), // 0xB0E0E6FF (пороховой голубой)
    Color(0.392f, 0.584f, 0.929f, 1.0f)  // 0x6495EDFF (васильковый)
)

val brownColors = listOf(
    Color(0.647f, 0.165f, 0.165f, 1.0f), // 0xA52A2AFF (коричневый)
    Color(0.545f, 0.271f, 0.075f, 1.0f), // 0x8B4513FF (шоколадный)
    Color(0.627f, 0.322f, 0.176f, 1.0f), // 0xA0522DFF (коричнево-красный)
    Color(0.804f, 0.522f, 0.247f, 1.0f), // 0xCD853FFF (перу)
    Color(0.737f, 0.561f, 0.561f, 1.0f), // 0xBC8F8FFF (розово-коричневый)
    Color(0.824f, 0.412f, 0.118f, 1.0f), // 0xD2691EFF (коричнево-оранжевый)
    Color(0.588f, 0.294f, 0.000f, 1.0f)  // 0x964B00FF (тёмно-коричневый)
)

val genomeEditorColor = listOf(
    // Зеленые оттенки
    Color(0.133f, 0.545f, 0.133f, 1.0f), // 0x228B22FF (лесной зеленый)
    Color(0.180f, 0.545f, 0.341f, 1.0f), // 0x2E8B57FF (морской зеленый)
    Color(0.420f, 0.557f, 0.137f, 1.0f), // 0x6B8E23FF (оливково-зеленый)
    Color(0.000f, 0.502f, 0.000f, 1.0f), // 0x008000FF (зеленый)
    Color(0.196f, 0.804f, 0.196f, 1.0f), // 0x32CD32FF (лаймовый зеленый)
    Color(0.125f, 0.698f, 0.667f, 1.0f), // 0x20B2AAFF (светло-морской зеленый)
    Color(0.678f, 1.000f, 0.184f, 1.0f), // 0xADFF2FFF (зеленый шартрез)

    // Синие оттенки
    Color(0.000f, 0.000f, 0.804f, 1.0f), // 0x0000CCFF (темно-синий)
    Color(0.118f, 0.565f, 1.000f, 1.0f), // 0x1E90FFFF (голубой)
    Color(0.000f, 0.749f, 1.000f, 1.0f), // 0x00BFFFFF (глубокий голубой)
    Color(0.255f, 0.412f, 0.882f, 1.0f), // 0x4169E1FF (королевский синий)
    Color(0.000f, 0.392f, 0.784f, 1.0f), // 0x0064C8FF (синий стальной)
    Color(0.275f, 0.510f, 0.706f, 1.0f), // 0x4682B4FF (стальной синий)
    Color(0.000f, 0.275f, 0.545f, 1.0f), // 0x00458AFF (темно-синий)

    // Фиолетовые оттенки
    Color(0.502f, 0.000f, 0.502f, 1.0f), // 0x800080FF (пурпурный)
    Color(0.545f, 0.000f, 0.545f, 1.0f), // 0x8B008BFF (темно-пурпурный)
    Color(0.576f, 0.439f, 0.859f, 1.0f), // 0x9370DBFF (средний фиолетовый)
    Color(0.294f, 0.000f, 0.510f, 1.0f), // 0x4B0082FF (индиго)
    Color(0.373f, 0.620f, 0.627f, 1.0f), // 0x5F9EA0FF (бирюзовый)
    Color(0.600f, 0.196f, 0.800f, 1.0f), // 0x9932CCFF (темно-орхидея)
    Color(0.855f, 0.439f, 0.839f, 1.0f), // 0xDAA2DFFF (орхидея)

    // Красные оттенки
    Color(0.804f, 0.000f, 0.000f, 1.0f), // 0xCC0000FF (темно-красный)
    Color(0.698f, 0.133f, 0.133f, 1.0f), // 0xB22222FF (огненно-красный)
    Color(0.545f, 0.000f, 0.000f, 1.0f), // 0x8B0000FF (темно-бордовый)
    Color(0.863f, 0.078f, 0.235f, 1.0f), // 0xDC143CFF (малиновый)
    Color(0.804f, 0.361f, 0.361f, 1.0f), // 0xCD5C5CFF (индийский красный)
    Color(0.502f, 0.000f, 0.000f, 1.0f), // 0x800000FF (бордовый)
    Color(0.941f, 0.502f, 0.502f, 1.0f), // 0xF08080FF (светло-коралловый)

    // Оранжевые оттенки
    Color(1.000f, 0.549f, 0.000f, 1.0f), // 0xFF8C00FF (темно-оранжевый)
    Color(1.000f, 0.647f, 0.000f, 1.0f), // 0xFFA500FF (оранжевый)
    Color(0.804f, 0.400f, 0.000f, 1.0f), // 0xCD6600FF (оранжево-коричневый)
    Color(0.941f, 0.502f, 0.502f, 1.0f), // 0xF08080FF (коралловый)
    Color(0.941f, 0.502f, 0.502f, 1.0f), // 0xF08080FF (светло-коралловый)
    Color(0.941f, 0.502f, 0.502f, 1.0f), // 0xF08080FF (светло-коралловый)

    // Желтые оттенки
    Color(1.000f, 0.843f, 0.000f, 1.0f), // 0xFFD700FF (золотой)
    Color(0.933f, 0.867f, 0.510f, 1.0f), // 0xEEDD82FF (светло-золотой)
    Color(0.941f, 0.902f, 0.549f, 1.0f), // 0xF0E68CFF (хаки)
    Color(0.941f, 0.902f, 0.549f, 1.0f), // 0xF0E68CFF (светло-хаки)
    Color(0.941f, 0.902f, 0.549f, 1.0f), // 0xF0E68CFF (светло-хаки)
    Color(0.941f, 0.902f, 0.549f, 1.0f), // 0xF0E68CFF (светло-хаки)
)
