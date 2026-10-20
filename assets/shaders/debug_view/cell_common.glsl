// Общая часть фрагментных шейдеров клеток отладочного режима: cell_fill.frag и
// cell_edge.frag. Вставляется на место строки #include, см. DebugRenderer.source,
// после cell_look.glsl.
//
// Оба прохода обязаны красить клетку ОДИНАКОВО: заливка рисует внутренность, проход
// сглаживания — полупиксельную полосу по её краю. Разойдись они в формулах цвета, на
// границе клеток появилась бы ступенька. Поэтому и входы, и рисунки — здесь.
//
// РИСУНКИ
// -------
// Схемы клеток нужны для того, чтобы с первого взгляда понять, что за клетка, — а не для
// красоты ради красоты. Поэтому каждый рисунок дешёвый: полярные координаты, повтор по
// углу или радиусу через fract/floor и одна-две выборки хэша на пиксель. Ни циклов по
// соседям, ни текстур, ни шума из нескольких октав. Подход подсмотрен у Cell Lab: там
// каждая клетка — пара сравнений в полярных координатах.
//
// Мелкие детали гаснут вместе с patternFade, когда клетка меньше нескольких пикселей:
// на отдалении клетку узнают по цвету, а узор превратился бы в рябь. Там же рисунок и
// не считается вовсе — дальний план стоит как раньше.
//
// Анимация — от часов u_time (RenderFrame.time) и сигнала действия v_action. Сигнал
// меняет РАЗМАХ движения, а не частоту: частота, зависящая от сигнала, сдвигала бы фазу
// рывком при каждой смене сигнала, и клетка дёргалась бы. Частоты — с не более чем
// тремя знаками после запятой: за период часов (TIME_WRAP = 1000 с) укладывается целое
// число оборотов, и на обороте картинка не прыгает. Исключение — гранулы, бегущие по сетке
// (источник феромона): раз в оборот часов, то есть раз в ~17 минут, они перетасуются.

in vec2 v_local;
flat in vec3 v_color;
flat in float v_tPerPixel;
flat in vec2 v_dir;
flat in int v_pattern;
flat in float v_action;
flat in float v_opacity;
flat in float v_key;
flat in vec4 v_rand;

/** Секунды, по кругу с периодом RenderFrame.TIME_WRAP. */
uniform float u_time;

/** Фон режима: сквозь прозрачные клетки «видно» именно его. */
uniform vec3 u_background;

/** Ширина мембраны — светлой полосы по краю тела, в пикселях. */
const float MEMBRANE_PX = 1.25;

/** Полуширина стенки между соседними клетками, в пикселях. */
const float WALL_HALF_PX = 0.6;

/** Непрозрачность стенки между клетками: стенка — затемнение, а не свой цвет. */
const float WALL_ALPHA = 0.45;

const float TAU = 6.2831853;

// ==================== Покрытие и владелец пикселя ====================

/**
 * Покрытие пикселя фигурой, если до её края [px] пикселей (плюс — внутрь).
 * Ящичный фильтр шириной в пиксель: ровно то, что дал бы MSAA с бесконечным числом
 * выборок, но за одно вычитание.
 */
float coverage(float px) {
    return clamp(0.5 + px, 0.0, 1.0);
}

/** То же, но расстояние со знаком в долях радиуса клетки, минус — внутри фигуры. */
float cov(float d) {
    return clamp(0.5 - d / v_tPerPixel, 0.0, 1.0);
}

/**
 * Градиент d/R клетки в пикселе, в долях радиуса на пиксель: направление от её центра
 * к пикселю, длина — tPerPixel. По разности градиентов двух клеток проход сглаживания
 * переводит разность их d/R в пиксели до границы между ними.
 */
vec2 tGradient(vec2 local, float t, float tPerPixel) {
    vec2 direction = t > 1e-6 ? local / t : vec2(1.0, 0.0);
    return direction * tPerPixel;
}

/**
 * Упаковка градиента владельца в RGBA8: rg — направление, b — tPerPixel в логарифме
 * (2^-14..1, шаг ~4 %: одинаково точно и для клетки в пару пикселей, и во весь экран),
 * a — пиксель занят клеткой и её группа слияния: (128 + группа) / 255. У пустоты после
 * очистки ноль, поэтому «занят» — это a > 0.25.
 */
vec4 encodeOwner(vec2 local, float t, float tPerPixel, int group) {
    vec2 direction = t > 1e-6 ? local / t : vec2(1.0, 0.0);
    float scale = clamp((log2(tPerPixel) + 14.0) / 14.0, 0.0, 1.0);
    return vec4(direction * 0.5 + 0.5, scale, float(128 + group) / 255.0);
}

vec2 decodeOwnerGradient(vec4 owner) {
    vec2 direction = owner.rg * 2.0 - 1.0;
    float len = length(direction);
    direction = len > 1e-3 ? direction / len : vec2(1.0, 0.0);
    return direction * exp2(owner.b * 14.0 - 14.0);
}

int ownerGroup(vec4 owner) {
    return int(floor(owner.a * 255.0 + 0.5)) - 128;
}

/**
 * Мелкие детали (мембрана, стенки, узор) гаснут, когда клетка становится меньше
 * нескольких пикселей: иначе на отдалении клетка из одних полос превращается в рябь.
 */
float detailFade(float tPerPixel) {
    return smoothstep(3.0, 8.0, 1.0 / tPerPixel);
}

/**
 * Рисунок клетки проступает позже мембраны: при радиусе меньше ~10 пикселей хлоропласты
 * и волокна уже не разобрать, клетку узнают по цвету. Ниже порога рисунок не считается
 * вовсе — дальний план стоит как плоский (замер на стенде: при радиусе 8 пикселей рисунок
 * на 20 000 клеток стоил почти столько же, сколько весь режим без него).
 */
float patternFade(float tPerPixel) {
    return smoothstep(10.0, 20.0, 1.0 / tPerPixel);
}

// ==================== Кирпичики рисунков ====================

vec4 paint(vec3 rgb, float alpha) {
    return vec4(rgb * alpha, alpha);
}

/** Премноженное «поверх». */
vec4 over(vec4 top, vec4 bottom) {
    return top + bottom * (1.0 - top.a);
}

/** Случайные числа для i-го элемента рисунка этой клетки. */
vec4 randAt(float i, float salt) {
    return hash4(vec2(i * 1.618034 + salt * 17.13, v_key + salt * 3.7));
}

vec4 randAt2(vec2 cell, float salt) {
    return hash4(cell + vec2(v_key * 0.37 + salt * 3.1, v_key * 0.11 + salt * 7.7));
}

/**
 * Угол точки, −π..π, как atan(p.y, p.x), но полиномом: ошибка ~0.001 рад, рисунку этого
 * хватает с запасом. Встроенный atan на Intel UHD — несколько десятков инструкций, а угол
 * нужен почти каждому рисунку на каждом пикселе. Шов у ±π тот же, что у atan, — на нём
 * держится mod(k, count) в повторах по углу.
 */
float angleOf(vec2 p) {
    vec2 a = abs(p);
    float t = min(a.x, a.y) / max(max(a.x, a.y), 1e-20);
    float s = t * t;
    float r = ((-0.0464964749 * s + 0.15931422) * s - 0.327622764) * s * t + t;
    if (a.y > a.x) r = 1.5707963 - r;
    if (p.x < 0.0) r = 3.1415927 - r;
    return p.y < 0.0 ? -r : r;
}

/** Фаза периодического движения 0..1. Про допустимые частоты — в начале файла. */
float cycle(float hz, float shift) {
    return fract(hz * u_time + shift);
}

float wave(float hz, float shift) {
    return sin(TAU * cycle(hz, shift));
}

/**
 * Линия полутолщиной halfW вокруг осевой, dist — расстояние до осевой (доли радиуса).
 * Тоньше полупикселя линия не сужается, а бледнеет: иначе на отдалении она рвалась бы
 * в пунктир.
 */
float stroke(float dist, float halfW) {
    float minHalf = 0.5 * v_tPerPixel;
    return cov(dist - max(halfW, minHalf)) * min(1.0, halfW / minHalf);
}

/** Расстояние до эллипса с полуосями halfAxes, приближённое (точное на осях). */
float ellipse(vec2 q, vec2 halfAxes) {
    return (length(q / halfAxes) - 1.0) * min(halfAxes.x, halfAxes.y);
}

/**
 * Россыпь гранул: по одной на ячейку сетки со стороной 1/density, есть с вероятностью
 * presence, радиус size — в долях ячейки, не больше ~0.2. Гранула не выходит за свою
 * ячейку, поэтому соседей проверять не нужно — одна выборка хэша на пиксель.
 * Возвращает покрытие и случайное число гранулы (для цвета и фазы).
 */
vec2 granules(vec2 p, float density, float presence, float size, float salt) {
    vec2 g = p * density;
    vec2 cell = floor(g);
    vec4 r = randAt2(cell, salt);
    vec2 center = 0.3 + 0.4 * r.yz;
    float d = (length(g - cell - center) - size * mix(0.6, 1.0, r.w)) / density;
    return vec2(r.x < presence ? cov(d) : 0.0, r.w);
}

/**
 * Сеть отростков из центра: count отростков (целое), у каждого своя длина, изгиб и
 * вилка на конце. r, theta — полярные координаты от центра сети. Каждый отросток живёт в
 * своём секторе угла, и весь его изгиб укладывается в сектор — поэтому достаточно одного
 * хэша на пиксель, без перебора отростков. tipHalfW — утолщение у мембраны (r > 0.84):
 * чувствительные окончания сенсора касания.
 */
float branches(float r, float theta, float count, float reach, float halfW, float tipHalfW, float salt) {
    float s = theta * (count / TAU) + fract((v_key + salt) * 0.618034);
    float k = floor(s);
    float u = fract(s) - 0.5;
    // mod — чтобы на шве угла ±π сектор остался тем же отростком.
    vec4 h = randAt(mod(k, count), salt);
    float len = reach * mix(0.55, 1.0, h.x);
    float axis = (h.y - 0.5) * 0.3 + 0.12 * sin(r * (4.0 + 3.0 * h.z) + TAU * h.w);
    float fork = len * mix(0.3, 0.6, h.z);
    float spread = 0.17 * clamp((r - fork) / max(len - fork, 1e-3), 0.0, 1.0);
    float d = min(abs(u - axis - spread), abs(u - axis + spread)) * (TAU / count) * r;
    float w = halfW * mix(1.0, 0.4, clamp(r / len, 0.0, 1.0)) + tipHalfW * smoothstep(0.84, 0.92, r);
    return stroke(d, w) * (1.0 - smoothstep(len - 0.05, len, r));
}

/**
 * Реснички по ободу (r > 1): count штук длиной до len, у основания полутолщина halfW.
 * Кончик отклоняется на amp долей промежутка между ресничками по волне: travel > 0 —
 * волна бежит от кончика к основанию (бьют к поверхности), < 0 — от основания к кончику,
 * 0 — ресничка качается целиком. lean — общий наклон всех ресничек в одну сторону.
 */
float cilia(float r, float theta, float count, float len, float halfW,
            float amp, float hz, float travel, float lean, float salt) {
    float s = theta * (count / TAU);
    float k = floor(s);
    float u = fract(s) - 0.5;
    vec4 h = randAt(mod(k, count), salt);
    float x = (r - 1.0) / (len * mix(0.7, 1.0, h.x));
    if (x > 1.0) return 0.0;
    x = max(x, 0.0);
    float bend = (amp * wave(hz, h.y + travel * x) + lean) * x * x;
    float d = abs(u - (h.z - 0.5) * 0.2 - bend) * (TAU / count) * r;
    return stroke(d, halfW * (1.0 - 0.6 * x)) * smoothstep(1.0, 0.8, x);
}

/** Треугольные шипы по ободу: длина и наклон у каждого свои. */
float spikes(float r, float theta, float count, float len, float baseHalf, float salt) {
    float s = theta * (count / TAU);
    float k = floor(s);
    float u = fract(s) - 0.5;
    vec4 h = randAt(mod(k, count), salt);
    float x = max(r - 1.0, 0.0) / (len * mix(0.55, 1.0, h.x));
    float center = (h.y - 0.5) * 0.24 + (h.z - 0.5) * 0.24 * x;
    float halfWidth = baseHalf * mix(0.7, 1.15, h.w) * (1.0 - x);
    return cov((abs(u - center) - halfWidth) * (TAU / count) * r);
}

// ==================== Тело клетки ====================

/**
 * Насколько клетка непрозрачна: доля её цвета против фона. Прозрачные клетки (лист,
 * присоска) темнее плотных (кость, каратель), но цвет типа остаётся главным признаком —
 * по нему клетку узнают издалека, где рисунка уже нет.
 */
float bodyDensity(int pattern) {
    if (pattern == P_OSTEON || pattern == P_SPIKES) return 0.9;
    if (pattern == P_MUSCLE || pattern == P_ZYGOTE || pattern == P_STEM) return 0.82;
    if (pattern == P_FAT_DROPLET || pattern == P_DIVIDING || pattern == P_PUMP_NEEDLES) return 0.7;
    if (pattern == P_SUCTION_RINGS || pattern == P_CHIP) return 0.72;
    if (pattern == P_SHORT_CILIA || pattern == P_STICKY_BUMPS) return 0.62;
    return 0.68;
}

/** Цвет ткани в точке t (доли радиуса от центра). */
vec3 bodyColor(float t) {
    vec3 base = v_color;
    int pattern = v_pattern;

    // Прежний вид: чистый цвет типа и лёгкий объём — к ободу чуть темнее.
    if (pattern == P_PLAIN) return base * mix(1.05, 0.86, t * t);

    vec3 tint = base;
    if (pattern == P_OSTEON) {
        tint = base * vec3(0.74, 0.72, 0.66);
    } else if (pattern == P_MUSCLE) {
        // Сжатие (сигнал < 0, укорочение связей) — темнее, растяжение — светлее.
        tint = mix(base, vec3(1.0), 0.3);
        tint = v_action < 0.0 ? tint * (1.0 + 0.55 * v_action) : mix(tint, vec3(1.0), 0.45 * v_action);
    } else if (pattern == P_STEM || pattern == P_ZYGOTE) {
        tint = mix(base, vec3(1.0), 0.3);
    } else if (pattern == P_SPIKES) {
        // Тело темнее цвета типа: иначе красные включения терялись бы на красном.
        tint = base * 0.55;
    }
    vec3 c = mix(u_background, tint, bodyDensity(pattern));

    // У сливающихся тканей объёма нет: он рисовал бы тёмный шов по каждой границе клеток.
    return mergeGroup(pattern) != 0 ? c : c * mix(1.04, 0.9, t * t);
}

/** Мембрана: светлее заливки, на тёмном фоне читается как оболочка тела. */
vec3 membraneColor(vec3 base) {
    return mix(base, vec3(1.0), 0.55);
}

/** У липкой клетки мембрана идёт по буграм за окружностью — её рисует бахрома. */
bool hasMembrane(int pattern) {
    return pattern != P_STICKY_BUMPS;
}

// ==================== Рисунки внутри клетки ====================
// p — точка в системе клетки (x — по её направлению), r = |p|, всё в долях радиуса.
// Результат — премноженный цвет поверх ткани.

vec4 chloroplasts(vec2 p, float r) {
    // Ядро — серое, чуть сбоку от центра.
    vec4 h = v_rand;
    vec2 nucleusAt = (h.xy - 0.5) * 0.18;
    float nucleus = ellipse(p - nucleusAt, vec2(0.17, 0.14));
    vec4 ink = paint(mix(v_color, vec3(0.85), 0.55) * 0.75, cov(nucleus));
    ink = over(paint(vec3(0.15), stroke(abs(nucleus), 0.012) * 0.6), ink);

    // Два пояса хлоропластов вдоль края: внешний быстрее, внутренний — навстречу. Это ток
    // цитоплазмы; группы — от низкой гармоники по кругу, у каждой клетки свои.
    bool outer = r > 0.59;
    float n = outer ? 16.0 : 10.0;
    float ring = outer ? 0.76 : 0.42;
    float turns = outer ? cycle(0.07, h.z) : -cycle(0.045, h.w);
    float s = (angleOf(p) / TAU + turns) * n;
    float k = mod(floor(s), n);
    vec4 c = randAt(k + (outer ? 0.0 : 32.0), 12.0);
    // Где гармоника низко — пусто, где высоко — плотная группа.
    float group = sin(TAU * (k / n * 2.0 + h.x)) + 0.5 * sin(TAU * (k / n * 3.0 + h.y));
    if (c.x > 0.5 + 0.5 * group) return ink;

    vec2 q = vec2((fract(s) - 0.5) * (TAU / n) * ring, r - ring - (c.y - 0.5) * 0.1);
    vec2 axes = (outer ? vec2(0.1, 0.045) : vec2(0.08, 0.038)) * mix(0.8, 1.1, c.z);
    float d = ellipse(q, axes);
    vec3 green = mix(vec3(0.25, 0.8, 0.22), v_color, 0.3) * mix(0.85, 1.1, c.w);
    return over(paint(green, cov(d)), ink);
}

vec4 fatDroplet(vec2 p, float r) {
    vec4 h = v_rand;
    bool hasNucleus = h.x < 0.65;
    float angle = TAU * h.y + 0.15 * wave(0.05, h.z);
    vec2 toNucleus = vec2(cos(angle), sin(angle));

    // Капля сдвинута от ядра — ядро зажато между ней и мембраной.
    vec2 center = hasNucleus ? -toNucleus * 0.07 : vec2(0.0);
    float radius = hasNucleus ? 0.8 : 0.86;
    vec2 q = p - center;
    float d = length(q) - radius;
    vec3 drop = mix(vec3(1.0, 0.95, 0.62), v_color, 0.3);
    drop *= mix(1.0, 0.86, smoothstep(-0.14, 0.0, d));
    drop += 0.1 * (1.0 - smoothstep(0.0, 0.45, length(q - vec2(-0.3, 0.3) * radius)));
    vec4 ink = paint(drop, cov(d));
    if (!hasNucleus) return ink;

    vec2 n = p - toNucleus * 0.86;
    vec2 local = vec2(dot(n, toNucleus), dot(n, vec2(-toNucleus.y, toNucleus.x)));
    return over(paint(v_color * 0.28, cov(ellipse(local, vec2(0.065, 0.11)))), ink);
}

vec4 osteon(vec2 p, float r) {
    vec4 h = v_rand;
    vec2 q = p - (h.xy - 0.5) * 0.2;
    float rr = length(q);
    float th = angleOf(q);
    // Неровность ламелл растёт к краю.
    float rho = rr * (1.0 + 0.07 * sin(3.0 * th + TAU * h.z) + 0.05 * sin(5.0 * th + TAU * h.w));
    // К ободу узор гаснет: у соседних костей он не совпадает, а сливаться они должны.
    float keep = 1.0 - smoothstep(0.72, 0.9, r);

    vec3 dark = v_color * 0.32;
    float l = (rho - 0.2) / 0.13;
    float lamella = stroke(abs(fract(l + 0.5) - 0.5) * 0.13, 0.011) * step(0.14, rho) * keep;
    vec4 ink = paint(dark, lamella * 0.45);

    // Редкие лакуны — вытянутые вдоль ламеллы, посередине между линиями.
    float band = floor(l);
    float m = 4.0 + band * 2.0;
    float s = th / TAU * m + h.x;
    vec4 c = randAt(mod(floor(s), m) + band * 16.0, 32.0);
    if (c.x < 0.4 && band >= 0.0 && band <= 3.0) {
        vec2 lq = vec2((fract(s) - 0.5) * (TAU / m) * rho, (fract(l) - 0.5) * 0.13);
        ink = over(paint(dark * 0.6, cov(ellipse(lq, vec2(0.055, 0.018))) * keep), ink);
    }

    // Центральный канал.
    return over(paint(dark * 0.8, cov(rr - 0.1)), ink);
}

vec4 flagellumBase(vec2 p, float r) {
    // Базальное тельце у мембраны там, откуда выходит жгутик, и корешок к центру.
    vec4 ink = paint(v_color * 0.6, stroke(length(vec2(p.x - clamp(p.x, 0.35, 0.82), p.y)), 0.02) * 0.7);
    ink = over(paint(v_color * 0.3, cov(length(p - vec2(0.84, 0.0)) - 0.07)), ink);
    vec2 g = granules(p, 6.0, 0.35, 0.12, 41.0);
    return over(paint(mix(v_color, vec3(1.0), 0.4), g.x * 0.5), ink);
}

vec4 neuron(vec2 p, float r) {
    vec4 h = v_rand;
    float th = angleOf(p);
    float activity = min(abs(v_action), 1.0);

    float count = 5.0 + floor(h.x * 4.0);
    float net = branches(r, th, count, 0.95, 0.032, 0.0, 52.0);
    vec3 pink = mix(v_color, vec3(1.0), 0.25 + 0.35 * activity);
    vec4 ink = paint(pink, net);

    float clot = r - 0.26 * (1.0 + 0.18 * sin(3.0 * th + TAU * h.y) + 0.1 * sin(5.0 * th + TAU * h.z));
    ink = over(paint(v_color * 0.55, cov(clot)), ink);

    vec2 g = granules(p, 7.0, 0.35, 0.12, 53.0);
    return over(paint(vec3(0.3, 0.1, 0.42), g.x * 0.9), ink);
}

vec4 muscle(vec2 p, float r) {
    vec4 h = v_rand;
    // Сжатие (сигнал < 0) — волокна толще: в поперечном срезе мышца при сокращении пухнет.
    float squeeze = 1.0 + 0.12 * v_action;
    vec2 q = p * 4.2 * squeeze;
    q += 0.22 * sin(q.yx * 1.3 + TAU * h.xy);

    // Шестиугольная решётка волокон: ближайший из двух сдвинутых центров. Искривление
    // выше делает волокна неравными, а смесь шестиугольника с кругом — мягкими.
    const vec2 S = vec2(1.0, 1.7320508);
    vec2 a = mod(q, S) - S * 0.5;
    vec2 b = mod(q - S * 0.5, S) - S * 0.5;
    vec2 g = dot(a, a) < dot(b, b) ? a : b;
    vec2 ag = abs(g);
    float hex = max(dot(ag, vec2(0.5, 0.8660254)), ag.x);
    vec4 c = randAt2(floor((q - g) * 2.0 + 0.5), 62.0);
    float fiber = (mix(hex, length(g), 0.5) - 0.4 * mix(0.85, 1.1, c.x)) / (4.2 * squeeze);

    vec3 gap = mix(u_background, v_color * 0.5, 0.8);
    vec4 ink = paint(gap, (1.0 - cov(fiber)) * 0.75);
    ink = over(paint(vec3(1.0), cov(fiber) * (c.y - 0.5) * 0.12), ink);

    vec2 gr = granules(p, 6.0, 0.18, 0.11, 63.0);
    return over(paint(vec3(0.55, 0.25, 0.78), gr.x * 0.9), ink);
}

vec4 ciliaInward(vec2 p, float r) {
    // Мягкая зернистость: много бледных крупинок.
    vec2 g = granules(p, 9.0, 0.8, 0.17, 71.0);
    return paint(mix(v_color, vec3(1.0), 0.35), g.x * 0.3);
}

vec4 stem(vec2 p, float r) {
    vec4 h = v_rand;
    float th = angleOf(p);
    float pulse = wave(0.25, h.x);

    // Неоднородность нутра.
    vec4 ink = paint(vec3(1.0), (0.5 + 0.5 * sin(p.x * 6.0 + TAU * h.y) * sin(p.y * 5.0 + TAU * h.z)) * 0.07);

    // Неровная внутренняя оболочка, медленно меняет форму.
    float shell = 0.86 + 0.03 * sin(4.0 * th + TAU * cycle(0.07, h.w)) + 0.02 * sin(7.0 * th - TAU * cycle(0.05, h.x)) + 0.015 * pulse;
    ink = over(paint(mix(v_color, vec3(1.0), 0.5), stroke(abs(r - shell), 0.02) * 0.5), ink);

    // Крупное тёплое розоватое ядро.
    vec2 q = p - (h.yz - 0.5) * 0.1;
    float nr = length(q) - 0.37 * (1.0 + 0.05 * pulse) - 0.025 * sin(3.0 * angleOf(q) + TAU * cycle(0.1, h.w));
    vec3 warm = mix(vec3(0.95, 0.58, 0.55), v_color, 0.25);
    ink = over(paint(warm * mix(1.05, 0.85, smoothstep(-0.3, 0.0, nr)), cov(nr)), ink);
    ink = over(paint(warm * 0.6, stroke(abs(nr), 0.012)), ink);
    return over(paint(warm * 0.55, cov(length(q - vec2(0.08, -0.05)) - 0.08)), ink);
}

vec4 excreta(vec2 p, float r) {
    // Ядро у мембраны со стороны направления — оттуда клетка выделяет глюкозу.
    vec2 q = p - vec2(0.6, 0.0);
    float d = ellipse(q, vec2(0.15, 0.24));
    vec4 ink = paint(v_color * 0.45, cov(d));
    ink = over(paint(v_color * 0.25, stroke(abs(d), 0.012)), ink);
    vec2 g = granules(p, 7.0, 0.3, 0.12, 91.0);
    return over(paint(vec3(0.95, 0.9, 0.7), g.x * 0.6), ink);
}

vec4 suctionRings(vec2 p, float r) {
    vec4 h = v_rand;
    float th = angleOf(p);
    float n = 5.0 + floor(h.x * 4.0);
    // Присасывание: кольца ритмично сжимаются, центр — сильнее. Сигнал — размах.
    float strength = 0.05 + 0.12 * clamp(v_action, 0.0, 1.0);
    float squeeze = 1.0 + strength * wave(0.8, h.y) * (1.0 - r);
    float rr = r * (1.0 + 0.06 * sin(3.0 * th + TAU * h.z) + 0.04 * sin(5.0 * th + TAU * h.w)) * squeeze;
    float spacing = 0.85 / n;
    float line = stroke(abs(fract(rr / spacing) - 0.5) * spacing, 0.017) * step(0.5 * spacing, rr) * (1.0 - smoothstep(0.82, 0.88, rr));
    vec4 ink = paint(v_color * 0.25, (1.0 - smoothstep(0.0, 0.85, rr)) * 0.45);
    return over(paint(mix(v_color, vec3(1.0), 0.4), line), ink);
}

vec4 stickyBumps(vec2 p, float r) {
    vec4 h = v_rand;
    // Нутро без контрастного узора — мягкие пятна.
    return paint(vec3(1.0), (0.5 + 0.5 * sin(p.x * 5.0 + TAU * h.x) * sin(p.y * 4.0 + TAU * h.y)) * 0.08);
}

vec4 pumpNeedles(vec2 p, float r) {
    vec4 h = v_rand;
    float th = angleOf(p);
    // Камеры внутри ритмично сжимаются — клетка перекачивает содержимое.
    float beat = wave(1.0, h.x);
    float ring = 0.45 * (1.0 + 0.1 * beat);
    float m = 5.0;
    float s = th * (m / TAU) + h.y;
    vec2 q = vec2((fract(s) - 0.5) * (TAU / m) * r, r - ring);
    float chamber = ellipse(q, vec2(0.16, 0.11) * (1.0 - 0.15 * beat));
    vec4 ink = paint(mix(v_color, vec3(1.0), 0.3), cov(chamber) * 0.8);
    ink = over(paint(v_color * 0.35, stroke(abs(chamber), 0.012)), ink);
    return over(paint(v_color * 0.3, cov(r - 0.14 * (1.0 - 0.25 * beat))), ink);
}

vec4 pigments(vec2 p, float r) {
    // Пигменты переливаются зелёным, красным и синим, каждый в своей фазе; ткань под
    // ними остаётся прозрачной и своего цвета (его задаёт сигнал хамелеона).
    vec2 g = granules(p, 6.0, 0.6, 0.19, 131.0);
    float phase = g.y + 0.15 * u_time;
    vec3 hue = 0.5 + 0.5 * cos(TAU * (phase + vec3(0.0, 0.333, 0.667)));
    return paint(hue, g.x * 0.85);
}

vec4 eye(vec2 p, float r) {
    vec4 h = v_rand;
    float th = angleOf(p);
    float count = 6.0 + floor(h.x * 4.0);
    float vessels = branches(r, th, count, 0.97, 0.018, 0.0, 142.0);
    vec4 ink = paint(mix(vec3(0.25, 0.45, 1.0), v_color, 0.3), vessels);
    float d = ellipse(p, vec2(0.21, 0.15));
    ink = over(paint(v_color * 0.4, cov(d)), ink);
    return over(paint(vec3(0.05, 0.08, 0.2), stroke(abs(d), 0.012)), ink);
}

vec4 synapse(vec2 p, float r) {
    vec4 h = v_rand;
    // Ядра медленно сходятся и частично срастаются, потом расходятся.
    float apart = 0.18 + 0.12 * (0.5 + 0.5 * wave(0.06, h.x));
    vec2 a = p - vec2(-apart, 0.0);
    vec2 b = p - vec2(apart, 0.0);

    float netA = branches(length(a), angleOf(a), 5.0, 0.95, 0.02, 0.0, 152.0);
    float netB = branches(length(b), angleOf(b), 5.0, 0.95, 0.02, 0.0, 153.0);
    vec4 ink = paint(mix(v_color, vec3(1.0, 0.75, 0.9), 0.4), netA);
    ink = over(paint(mix(v_color, vec3(1.0), 0.45), netB), ink);

    // Мягкое объединение: где ядра близко, между ними перемычка.
    float da = ellipse(a, vec2(0.16, 0.13));
    float db = ellipse(b, vec2(0.16, 0.13));
    float k = 0.07;
    float m = clamp(0.5 + 0.5 * (db - da) / k, 0.0, 1.0);
    float d = mix(db, da, m) - k * m * (1.0 - m);
    ink = over(paint(v_color * 0.4, cov(d)), ink);
    return over(paint(v_color * 0.2, stroke(abs(d), 0.012)), ink);
}

vec4 touchNet(vec2 p, float r) {
    vec4 h = v_rand;
    float th = angleOf(p);
    float count = 6.0 + floor(h.x * 4.0);
    float glow = clamp(abs(v_action), 0.0, 1.0);
    vec3 nerve = mix(v_color, vec3(1.0), 0.35 + 0.4 * glow);
    // Отростки доходят до самой мембраны и кончаются утолщениями.
    vec4 ink = paint(nerve, branches(r, th, count, 1.08, 0.016, 0.026, 162.0));
    float d = length(p) - 0.13;
    ink = over(paint(v_color * 0.35, cov(d)), ink);
    return over(paint(nerve, stroke(abs(d), 0.015)), ink);
}

vec4 zygote(vec2 p, float r) {
    vec4 h = v_rand;
    float pulse = wave(0.2, h.x);
    vec4 ink = paint(mix(v_color, vec3(1.0), 0.5), stroke(abs(r - 0.92 * (1.0 + 0.02 * wave(0.2, h.x + 0.25))), 0.015) * 0.5);
    float nr = length(p) - 0.42 * (1.0 + 0.04 * pulse);
    vec3 nucleus = mix(v_color, vec3(0.55, 0.35, 0.6), 0.55);
    ink = over(paint(nucleus * mix(1.1, 0.8, smoothstep(-0.4, 0.0, nr)), cov(nr)), ink);
    ink = over(paint(nucleus * 0.5, stroke(abs(nr), 0.015)), ink);
    return over(paint(nucleus * 0.4, cov(length(p - (h.yz - 0.5) * 0.25) - 0.09)), ink);
}

vec4 dividing(vec2 p, float r) {
    vec4 h = v_rand;
    // Два крупных ядра вдоль направления клетки; расстояние и форма медленно дышат.
    float apart = 0.25 + 0.04 * wave(0.08, h.x);
    vec2 axesA = vec2(0.24, 0.29) * (1.0 + 0.05 * wave(0.11, h.y));
    vec2 axesB = vec2(0.22, 0.27) * (1.0 + 0.05 * wave(0.09, h.z));
    float da = ellipse(p - vec2(-apart, 0.0), axesA);
    float db = ellipse(p - vec2(apart, 0.0), axesB);
    float d = min(da, db);
    vec3 nucleus = mix(v_color, vec3(0.45, 0.2, 0.3), 0.4);
    vec4 ink = paint(nucleus, cov(d));
    ink = over(paint(nucleus * 0.45, stroke(abs(da), 0.014) + stroke(abs(db), 0.014)), ink);
    float nucleoli = min(length(p - vec2(-apart - 0.05, 0.06)), length(p - vec2(apart + 0.04, -0.05))) - 0.06;
    return over(paint(nucleus * 0.4, cov(nucleoli)), ink);
}

/** Гранулы феромона: бегут от центра наружу; за окружностью их рисует бахрома. */
vec4 emitterGranules(vec2 p, float r) {
    float th = angleOf(p);
    float presence = 0.35 + 0.35 * clamp(v_action, 0.0, 1.0);
    vec2 q = vec2(th / TAU * 11.0, r * 5.0 - 0.4 * u_time);
    vec2 g = granules(q, 1.0, presence, 0.16, 191.0);
    vec3 hue = 0.55 + 0.45 * cos(TAU * (g.y + vec3(0.0, 0.333, 0.667)));
    // Гранулы у центра сжаты сеткой углов в точку — там их не рисуем.
    return paint(hue, g.x * 0.9 * smoothstep(0.15, 0.3, r));
}

vec4 longCilia(vec2 p, float r) {
    vec2 g = granules(p, 6.0, 0.4, 0.12, 201.0);
    return paint(mix(v_color, vec3(1.0), 0.4), g.x * 0.4);
}

vec4 spikesInside(vec2 p, float r) {
    // Много мелких красных включений.
    vec2 g = granules(p, 8.0, 0.8, 0.17, 211.0);
    return paint(vec3(1.0, 0.22, 0.12) * mix(0.8, 1.1, g.y), g.x);
}

vec4 shortCilia(vec2 p, float r) {
    vec2 g = granules(p, 7.0, 0.25, 0.12, 221.0);
    return paint(mix(v_color, vec3(1.0), 0.4), g.x * 0.35);
}

/** Прямоугольник со скруглёнными углами, полуразмер halfSize. */
float roundBox(vec2 q, vec2 halfSize, float radius) {
    vec2 d = abs(q) - halfSize + radius;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - radius;
}

vec4 chip(vec2 p, float r) {
    vec4 h = v_rand;
    float th = angleOf(p);
    // Волокна из-под чипа во все стороны.
    float count = 7.0 + floor(h.x * 3.0);
    vec4 ink = paint(mix(v_color, vec3(1.0), 0.35), branches(r, th, count, 0.97, 0.016, 0.0, 232.0));

    // Ножки: по три с каждой стороны — складываем четыре стороны в одну через abs и swap.
    vec2 a = abs(p);
    vec2 side = a.x > a.y ? a : a.yx;
    vec2 pin = vec2(side.x - 0.2, side.y - clamp(floor(side.y / 0.085 + 0.5) * 0.085, 0.0, 0.085));
    float pins = roundBox(pin, vec2(0.035, 0.018), 0.008);
    vec3 metal = mix(v_color, vec3(0.85), 0.5);
    ink = over(paint(metal, cov(pins)), ink);

    // Корпус и кристалл.
    float body = roundBox(p, vec2(0.17), 0.035);
    ink = over(paint(mix(vec3(0.08, 0.08, 0.12), v_color, 0.25), cov(body) * 0.9), ink);
    ink = over(paint(mix(v_color, vec3(1.0), 0.2) * 0.55, cov(roundBox(p, vec2(0.08), 0.015))), ink);

    // Антенна: от корпуса вбок от направления клетки, с шариком на конце.
    float antenna = length(vec2(p.x, p.y - clamp(p.y, 0.17, 0.4)));
    ink = over(paint(metal, stroke(antenna, 0.012)), ink);
    return over(paint(metal, cov(length(p - vec2(0.0, 0.42)) - 0.035)), ink);
}

vec4 patternInk(vec2 p, float r) {
    switch (v_pattern) {
        case P_CHLOROPLASTS: return chloroplasts(p, r);
        case P_FAT_DROPLET: return fatDroplet(p, r);
        case P_OSTEON: return osteon(p, r);
        case P_FLAGELLUM_BASE: return flagellumBase(p, r);
        case P_NEURON: return neuron(p, r);
        case P_MUSCLE: return muscle(p, r);
        case P_CILIA_INWARD: return ciliaInward(p, r);
        case P_STEM: return stem(p, r);
        case P_EXCRETA: return excreta(p, r);
        case P_SUCTION_RINGS: return suctionRings(p, r);
        case P_STICKY_BUMPS: return stickyBumps(p, r);
        case P_PUMP_NEEDLES: return pumpNeedles(p, r);
        case P_PIGMENTS: return pigments(p, r);
        case P_EYE: return eye(p, r);
        case P_SYNAPSE: return synapse(p, r);
        case P_TOUCH_NET: return touchNet(p, r);
        case P_ZYGOTE: return zygote(p, r);
        case P_DIVIDING: return dividing(p, r);
        case P_EMITTER_GRANULES: return emitterGranules(p, r);
        case P_LONG_CILIA: return longCilia(p, r);
        case P_SPIKES: return spikesInside(p, r);
        case P_SHORT_CILIA: return shortCilia(p, r);
        case P_CHIP: return chip(p, r);
        default: return vec4(0.0);
    }
}

/** Точка пикселя в системе клетки: x — по её направлению. */
vec2 cellFrame() {
    return vec2(dot(v_local, v_dir), dot(v_local, vec2(-v_dir.y, v_dir.x)));
}

/** Цвет клетки внутри окружности, в точке t (доли радиуса). */
vec3 cellColor(float t) {
    vec3 color = bodyColor(t);
    float fade = patternFade(v_tPerPixel);
    if (v_pattern != P_PLAIN && fade > 0.0) {
        vec4 ink = patternInk(cellFrame(), t) * fade;
        color = color * (1.0 - ink.a) + ink.rgb;
    }
    return color;
}

/** Цвет самого обода — с учётом того, что у мелкой клетки мембраны уже нет. */
vec3 rimColor(float fade) {
    vec3 body = bodyColor(1.0);
    return hasMembrane(v_pattern) ? mix(body, membraneColor(v_color), fade) : body;
}

// ==================== Бахрома за окружностью ====================

/**
 * Реснички, иглы, шипы, бугры и выходящие частицы — за окружностью клетки (t > 1).
 * Вызывает только проход сглаживания и только над пустотой.
 */
vec4 fringeInk(vec2 p, float r) {
    float th = angleOf(p);
    vec3 light = mix(v_color, vec3(1.0), 0.3);

    if (v_pattern == P_CILIA_INWARD) {
        return paint(light, cilia(r, th, 28.0, 0.22, 0.018, 0.28, 1.2, 3.0, 0.0, 301.0));
    }
    if (v_pattern == P_LONG_CILIA) {
        float strength = 0.25 + 0.1 * clamp(abs(v_action), 0.0, 1.0);
        return paint(light, cilia(r, th, 16.0, 0.6, 0.014, strength, 0.5, 0.0, 0.0, 302.0));
    }
    if (v_pattern == P_SHORT_CILIA) {
        // Реснички отвечают на поток: сигнал датчика — их общий наклон и размах.
        float a = clamp(v_action, -1.0, 1.0);
        return paint(light, cilia(r, th, 44.0, 0.13, 0.011, 0.12 + 0.2 * abs(a), 2.0, -2.0, 0.15 * a, 303.0));
    }
    if (v_pattern == P_PUMP_NEEDLES) {
        return paint(mix(v_color, vec3(1.0), 0.15), cilia(r, th, 34.0, 0.26, 0.012, 0.0, 0.0, 0.0, 0.0, 304.0));
    }
    if (v_pattern == P_SPIKES) {
        return paint(v_color * 0.7, spikes(r, th, 14.0, 0.3, 0.32, 305.0));
    }
    if (v_pattern == P_STICKY_BUMPS) {
        // Бугристая, но непрерывная поверхность; бугры колышутся, сигнал их раздувает.
        vec4 h = v_rand;
        float height = 0.09 * (1.0 + 0.6 * clamp(v_action, 0.0, 1.0));
        float edge = 1.0 + height * (0.55 + 0.25 * sin(7.0 * th + TAU * h.x) + 0.2 * sin(11.0 * th - TAU * cycle(0.2, h.y)));
        vec4 ink = paint(bodyColor(1.0), cov(r - edge));
        return over(paint(membraneColor(v_color), stroke(abs(r - edge), 0.02) * cov(r - edge - 0.02)), ink);
    }
    if (v_pattern == P_EMITTER_GRANULES) {
        vec4 ink = emitterGranules(p, r);
        return ink * (1.0 - smoothstep(1.0, 1.35, r));
    }
    if (v_pattern == P_EXCRETA) {
        // Глюкоза: по одной частице из каждого из трёх «выпусков», у каждой свой путь.
        float bright = mix(0.45, 1.0, clamp(v_action * 4.0, 0.0, 1.0));
        vec4 ink = vec4(0.0);
        for (int i = 0; i < 3; i++) {
            float phase = cycle(0.5, float(i) / 3.0);
            float launch = floor(0.5 * u_time + float(i) / 3.0);
            vec4 h = randAt(launch * 3.0 + float(i), 307.0);
            vec2 at = vec2(1.0 + 0.55 * phase, (h.x - 0.5) * 0.5 * phase);
            float d = length(p - at) - 0.05 * (1.0 - 0.4 * phase);
            ink = over(paint(vec3(0.98, 0.92, 0.72), cov(d) * (1.0 - phase) * bright), ink);
        }
        return ink;
    }
    return vec4(0.0);
}
