// Вид клетки в отладочном режиме — общая часть cell.vert, cell_fill.frag и cell_edge.frag.
// Вставляется на место строки #include, см. DebugRenderer.source.
//
// Номера рисунков — CellLook в :render (поле look инстанса). Менять только вместе.

const int P_PLAIN = 0;
const int P_CHLOROPLASTS = 1;
const int P_FAT_DROPLET = 2;
const int P_OSTEON = 3;
const int P_FLAGELLUM_BASE = 4;
const int P_NEURON = 5;
const int P_MUSCLE = 6;
const int P_CILIA_INWARD = 7;
const int P_STEM = 8;
const int P_EXCRETA = 9;
const int P_SUCTION_RINGS = 10;
const int P_STICKY_BUMPS = 11;
const int P_PUMP_NEEDLES = 12;
const int P_PIGMENTS = 13;
const int P_EYE = 14;
const int P_SYNAPSE = 15;
const int P_TOUCH_NET = 16;
const int P_ZYGOTE = 17;
const int P_DIVIDING = 18;
const int P_EMITTER_GRANULES = 19;
const int P_LONG_CILIA = 20;
const int P_SPIKES = 21;
const int P_SHORT_CILIA = 22;
const int P_CHIP = 23;
const int P_COUNT = 24;

/**
 * Насколько рисунок выходит за окружность клетки, в долях радиуса: реснички, иглы, шипы,
 * бугры, выходящие частицы. Эту бахрому рисует проход сглаживания и только над пустотой —
 * внутри тела её закрывают соседи, как и в жизни. Корпус клетки в этом проходе раздут на
 * эту длину, поэтому у остальных клеток здесь ноль: лишние фрагменты не бесплатны.
 */
float fringeLength(int pattern) {
    if (pattern == P_CILIA_INWARD) return 0.22;
    if (pattern == P_EXCRETA) return 0.6;
    if (pattern == P_STICKY_BUMPS) return 0.16;
    if (pattern == P_PUMP_NEEDLES) return 0.26;
    if (pattern == P_EMITTER_GRANULES) return 0.35;
    if (pattern == P_LONG_CILIA) return 0.6;
    if (pattern == P_SPIKES) return 0.3;
    if (pattern == P_SHORT_CILIA) return 0.13;
    return 0.0;
}

/**
 * Группа слияния: соседи одной группы рисуются без стенки между собой и без затемнения к
 * ободу — кость и мышца читаются сплошной тканью, а не мешком шариков. 0 — не сливается.
 */
int mergeGroup(int pattern) {
    if (pattern == P_OSTEON) return 1;
    if (pattern == P_MUSCLE) return 2;
    return 0;
}

/**
 * Хэш: четыре случайных числа 0..1 из точки p. «Hash without sine» Дейва Хоскинса —
 * только fract и умножения.
 *
 * Целочисленный хэш (lowbias32 на uint) здесь был и не прошёл: на Intel UHD под Windows
 * он возвращал ноль при любом входе — проверено снимком на стенде, все отростки и гранулы
 * выходили одинаковыми. fract(sin(x)) тоже не годится: на мобильных GPU sin теряет
 * точность при больших аргументах, и узор полосит. Рисунку не нужна детерминированность
 * между GPU, но нужна равномерность.
 */
vec4 hash4(vec2 p) {
    vec4 p4 = fract(vec4(p.xyxy) * vec4(0.1031, 0.1030, 0.0973, 0.1099));
    p4 += dot(p4, p4.wzxy + 33.33);
    return fract((p4.xxyz + p4.yzzw) * p4.zywx);
}
