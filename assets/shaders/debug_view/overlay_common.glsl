// Общая часть overlay.vert и overlay.frag: виды фигур и их размеры.
// Вставляется на место строки #include, см. DebugRenderer.compile.
//
// Размеры — в единицах мира (радиус клетки обычно около 0.5), поэтому при приближении
// фигуры растут вместе с клетками. Чтобы на отдалении линии не пропадали, у ширины есть
// нижний предел в пикселях; всё, что тоньше него, не сужается дальше, а гаснет по альфе.

// Виды — OverlayInstanceBuffer.KIND_*.
const int KIND_RING = 0;
const int KIND_RAY = 1;
const int KIND_ARROW = 2;
const int KIND_NEURAL = 3;
const int KIND_FLAGELLUM = 4;

// ---- нейросвязь ----
const float LINK_HALF = 0.022;
const float LINK_MIN_HALF_PX = 0.75;
const float CHEVRON_LEN = 0.17;
const float CHEVRON_HALF = 0.1;
const float BEAD_RADIUS = 0.065;
const float BEAD_MIN_PX = 1.6;
const float BEAD_HALO = 2.4;          // ореол положительного импульса, в радиусах бусины
const float PULSE_SPACING = 0.5;      // расстояние между импульсами, мир

// Импульсов в секунду через точку связи. Произведение на период времени
// (RenderFrame.TIME_WRAP) обязано быть целым — иначе на обороте времени бусины прыгнут.
const float PULSE_HZ = 2.0;

// ---- стрелка направления ----
const float SHAFT_HALF = 0.03;
const float SHAFT_MIN_HALF_PX = 0.75;
const float HEAD_LEN = 0.2;
const float HEAD_HALF = 0.12;

// ---- луч глаза ----
const float RAY_CORE_HALF = 0.014;
const float RAY_MIN_HALF_PX = 0.6;
const float RAY_GLOW_HALF = 0.09;
const float RAY_TICK_HALF = 0.09;     // поперечная черта на конце — предел дальности

// ---- жгутик хвоста ----
// Размеры — в долях длины жгутика: радиуса клетки оверлей не знает, а длину жгутику
// задаёт :core в радиусах клетки (DebugCellPalette.FLAGELLUM_LENGTH).
const float FLAGELLUM_HALF = 0.03;        // полутолщина у основания
const float FLAGELLUM_MIN_HALF_PX = 0.6;
const float FLAGELLUM_AMPLITUDE = 0.11;   // размах волны на кончике при полной тяге
const float FLAGELLUM_WAVELENGTH = 0.45;
const float FLAGELLUM_HZ = 1.5;           // см. PULSE_HZ: произведение на TIME_WRAP целое

// ---- кольцо (радиус феромона) ----
const float RING_HALF_PX = 0.7;

// Тёмная обводка линий и стрелок, в пикселях: чтобы они читались на клетке любого цвета.
const float OUTLINE_PX = 0.8;
