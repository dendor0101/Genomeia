const int FILTER_ENERGY = 1;
const int FILTER_SIGNAL = 2;
const int FILTER_COLLISION = 3;
const int FILTER_SLEEP = 4;

/**
 * Цвет клетки под фильтром. Значение — четвёртый байт look (CellLook.filter):
 *  - энергия: заполненность 0..1 — от синего (0, 0, 1) к пурпурному (1, 0, 1);
 *  - нейросигнал: знаковый байт уже нормализованного сигнала (CellLook.signalFilterByte) —
 *    ноль синий, плюс к пурпурному, минус к голубому (0, 1, 1): тормозящий сигнал
 *    отличается от возбуждающего, а молчащая клетка — от обеих;
 *  - коллизии: частица в сетке столкновений (CellLook.collisionFilterByte) — оранжевая,
 *    вне её — тёмно-синяя. Двух цветов хватает: признак да/нет, а оранжевый на тёмном
 *    синем виден и на мелких клетках дальнего плана.
 */
vec3 filterColor(int kind, float value) {
    if (kind == FILTER_COLLISION) return value > 0.0 ? vec3(1.0, 0.55, 0.0) : vec3(0.08, 0.12, 0.45);
    if (kind == FILTER_ENERGY) return vec3(value / 255.0, 0.0, 1.0);
    int steps = int(value);
    if (steps > 127) steps -= 256;
    float signal = float(steps) / 127.0;
    return signal >= 0.0 ? vec3(signal, 0.0, 1.0) : vec3(0.0, -signal, 1.0);
}

vec3 debugColor(vec3 color, float filterByte, bool isCell, int u_filter, out float filtered) {
    filtered = 0.0;
    if (u_filter == FILTER_SLEEP) {
        // Сон: спящая частица (клетка или нет) — ледяная, бодрствующая остаётся как есть —
        // так спящие участки видны поверх обычного отладочного вида.
        if (filterByte > 0.0) {
            color = vec3(0.55, 0.9, 1.0);
            filtered = 1.0;
        }
    } else if (u_filter != 0) {
        // Не-клетка (еда, субстанции) ни энергии, ни сигнала не несёт — гасим её до
        // серого, чтобы не путалась со шкалой фильтра. В коллизиях же она участвует, как
        // и клетки, — её фильтр закрашивает тоже.
        if (isCell || u_filter == FILTER_COLLISION) {
            color = filterColor(u_filter, filterByte);
            filtered = 1.0;
        } else {
            color = vec3(dot(color, vec3(0.299, 0.587, 0.114)) * 0.35);
        }
    }
    return color;
}
