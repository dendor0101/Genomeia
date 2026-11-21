# Как написать сцену стенда физики

Памятка для того, кто добавляет сцены в каталог стендов (`physics-lab/.../lab/stands`). Общее
описание стендов, правил и окна — в [stands.md](stands.md).

## Что такое сцена

Сцена — маленький мир физики, собранный так же, как его собирает игра, и сценарий по тикам.
За прогоном следит монитор правил: одни и те же правила на все сцены (числа конечны, энергия не
растёт, нет клеток в чужой ткани, никто не проходит сквозь ткань, нет клеток в своей ткани,
ничто не входит в рельеф, разрывы и гибель в пределах допуска, треугольники не вывернуты, кости
держат форму, спящее не двигается, нет дрожи, тело вернулось к форме, сцена успокоилась, всё
уснуло). Сцена выбирает только допуски (`Limits`) и добавляет свои ожидания (пробы).

Единицы — игры: клетка радиуса 0.5, связь 0.6. Скорости — в связях за тик (потолок решателя —
8). Тик — 1/30 секунды.

Каждая сцена на решётке автоматически идёт в шести вариациях (длины связей, радиусы клеток).
Отсюда главное правило: **размеры фигуры читаются только внутри сборки сцены** (`shape.x`,
`shape.y`, `shape.halfWidth`, `shape.halfHeight`, `shape.reach`) — снаружи они относятся к
ровной решётке. Номера клеток (`extreme`, `nearest`, `edgeCells`, `sideCells`) можно считать где
угодно: они у всех вариаций одни.

## Заготовка файла

```kotlin
package io.github.some_example_name.physics.lab.stands

/*
 * СЕМЕЙСТВО «…»: что в нём проверяется и зачем — два-три предложения по-русски.
 */
internal fun extraSomethingFamily(): List<Scenario> {
    val family = Family("15. Доп.: название")

    val shape = Shapes.square(8)
    family.add("xsome/square8/rest", "квадрат 8: покой",                       // номер латиницей и название
        "Одно тело в позе покоя, без толчков.",                                 // что на сцене
        "Ничего не происходит: тело не ползёт, не дрожит и засыпает.",          // что должно произойти
        150, Limits.INTACT) {                                                   // тиков и допуски
        body(shape, 0.0, 0.0)                                                   // сборка сцены
    }
    return family.list
}
```

Номер сцены — латиница, цифры, `-`, `/`, `.`; у каждой сцены свой. Текст — по-русски, простыми
словами: «что на сцене» и «что должно произойти» читает человек рядом с картинкой.

## Сборка сцены (внутри фигурных скобок `this` — мир стенда)

Тела и частицы:
- `body(shape, x, y, angle = 0.0): StandBody` — взрослое тело, центр в точке игры.
- `growing(shape, x, y, angle = 0.0, seed = клетка, start = 0, period = 2, batch = 1): StandBody` —
  тело растёт из клетки `seed`, поставленной в точку; `batch = 0` — слоями.
- `ball(x, y, vx = 0.0, vy = 0.0): Int` — свободная частица.
- `rock(x, y)` — неподвижная частица рельефа.
- `arena(ширина, высота)` — рамка мира со стенкой (чётные числа), угол в нуле.
- `terrain(originX, originY, cols, rows, jitter = true) { ix, iy -> твёрдая ли клетка }` — рельеф,
  шаг клетки карты `TERRAIN_STEP` (2/3).
- `cavity(cols, rows, jitter) { ix, iy -> свободна ли клетка }: Cavity` — замкнутая полость;
  у неё `minX, minY, maxX, maxY`. `fill(cavity, шаг, скорость = 0.0)` — набить частицами.
- `terrainLayouts()` — готовые раскладки рельефа (`wall, pillar, spikes, rough, ledge, slit,
  corner, corridor, funnel, pocket`); `place(layout, faceX, jitter = true)` ставит лицевой
  стороной в `faceX`; `approach(shape, x, y, angle, ux, uy)` — на сколько подвинуть тело, чтобы
  до рельефа осталось три связи.

Расстановка без наложения:
- `duel(a, b, speed, offset = 0.0, spin = 0.0, angleA = 0.0, angleB = 0.0): Pair<StandBody, StandBody>` —
  два тела по оси X, левое летит в правое; до касания три связи.
- `duelBoth(a, b, each)` — оба летят навстречу.
- `separation(a, b, CONTACT_DISTANCE)` — расстояние между центрами, при котором тела касаются;
  прибавьте `START_GAP` (три связи), чтобы не касались.
- `clearance(bodyA, bodyB)` — зазор между телами сейчас.

Воздействия:
- `kick(body, vx, vy, spin = 0.0)`; `kickCells(body, cells, vx, vy)`.
- `muscle(body, group, command)`, `muscles(body, command)`: 0.5f — сокращение, 1f — покой, 1.5f — расслабление.
- мышь: `grab(body, cell)`, `dragTo(x, y)`, `release()`; `mousePath(body, cell, startTick, ticksPerPoint, points)`;
  `circlePath(cx, cy, r, steps)`.
- присоска: `grip(body, cell или cells, 0f..1f)`.
- зажим: `val c = clamp(body, cells)`; `c.moveTo(dx, dy, angle)` каждый тик; `c.release()`.
- `killCells(body, cells)` — клетки гибнут; `cut(body) { a, c -> резать ли связь }`; `pieces(body)`.
- `world.deepKillOn = false` — выключить гибель от глубокого касания; `noSleep()` — выключить сон.

Сценарий: `at(tick) { … }`, `during(from, to) { t -> … }`.

Ожидания:
- `probe("имя") { число }` и `expectMaxAtLeast(text, probe, from, to, bound)`, `expectMaxAtMost`,
  `expectMinAtMost`, `expectMinAtLeast`.
- готовые: `expectMet(a, b, until, speed)` — тела встретились; `expectPushed(target, speed, until)` —
  стоявшее тело получило толчок; `expectStill(name, body, cells, from, until, links)` — клетки
  остались на месте; `expectTravelled(name, body, cells, from, until, links, text)`;
  `expectReached(body, until)` — тело дошло до рельефа; `expectAllInside(minX, minY, maxX, maxY, until)`;
  `expectContained(cavity, until)` — частицы остались в полости.
- чтение: `centerOf(body)`, `centerOfCells(body, cells)`, `speedOf(body)`, `distance(body, cellA, cellB)`,
  `gameX(p)`, `gameY(p)`, `body.particle(cell)`, `tick`.

Сцена, которая может «пройти, ничего не проверив» (снаряд пролетел мимо, тело не долетело), обязана
иметь ожидание, которое это ловит.

## Фигуры

`Shapes.square(n)`, `disc(r)`, `rect(cols, rows)`, `rope(n)`, `ribbon(n)`, `triangle(side)` (сторона
кратна трём), `ellipse(a, b)`, `ring(outer, inner)`, `lShape(n, t)`, `uShape(w, h, t)`,
`cShape(outer, inner)`, `cross(arm, thick)`, `star(points, outer, inner)`, `comb(teeth, toothW, toothH, baseH)`,
`dumbbell(r, bridge, thick)`, `crescent(r)`, `frame(n, t)`, `gear(r, teeth)`, `hourglass(w, h, neck)`,
`snake(len, amp, thick)`, `blob(seed, r)`, `coral(seed, parts)`.

Кости: `.boneCore(share = 0.5)`, `.boneSpine()`, `.boneShell()`, `.boneLeft()`, `.bonePlates(joint = 3)`,
`.boneAll()`, `.boneScattered(seed, clusters, size)`.
Мышцы: `.muscleAll()`, `.muscleTopBottom()`, `.muscleLeftRight()`, `.muscleSegments(n)`, `.muscleBand(share)`,
`.muscleRim()`, `.muscleQuadrants()`, `.muscleArms(arms, inner)`.
Готовые тела с костями и мышцами: `armShape(cols, rows)`, `pistonShape()`, `clawShape()`, `ribsShape()`,
`fishShape()`, `tadpoleShape()`, `coreRingShape()`, `tugShape()`.

У фигуры: `id`, `name`, `count`, `muscleGroups`, `boneCount`, `extreme(dx, dy)`, `nearest(x, y)`,
`lx[k]`, `ly[k]` (узлы ровной решётки — для выбора клеток), `boneClusters()`.
Выбор клеток: `edgeCells(shape, dx, dy, depth = 0.6)`, `sideCells(shape, dx, dy)`.

## Допуски

- `Limits.INTACT` — ничего не рвётся и не гибнет, к концу всё стоит и спит.
- `Limits.DRIVEN` — то же, но энергию вносят мышцы, мышь, рост, зажимы.
- `Limits.hit(speed)` — удар на скорости сближения `speed`: порваться может доля связей (2 — 4%, 4 — 36%, от 6 — сколько угодно).
- `Limits.CRASH` — разрушение допустимо; `Limits.PACKED` — сжатая сцена обязана успокоиться.
- готовые: `YANK` (рывок мышью), `TOUCH`, `GROW`, `GROW_TIGHT`, `GROW_JAM`, `FLICKERING`.
- свои: `Limits.DRIVEN.copy(name = "…", shape = false, sleep = false, jitter = false)`; поля — `tornShare`,
  `deadShare`, `capHits`, `energy` (`Energy.CLOSED`, `SETTLES`, `DRIVEN`), `calm`, `sleep`, `shape`, `inversions`, `jitter`.

Силы удара: `TAP` (0.15), `BUMP` (0.6), `HIT` (2), `RAM` (4), `CRASH` (6), `MAX_HIT` (8) — у каждой
`id`, `title`, `speed`, `limits`, `expect`, `rough`. Режимы мышц: `SQUEEZE`, `PUMP`, `FLICKER`, `SWAP`,
`SWAP_FAST`, `WAVE`, `HOLD` — у каждого `id`, `title`, `about`, `active` и `script(this, body)`. Темп роста:
`SLOW`, `FAST`, `LAYERS` — `period`, `batch`, `ticks(shape, seedCell)`.

Длительность: `settle(speed)` — сколько тиков телу нужно, чтобы остановиться и уснуть после
толчка `speed` (сцена, где всё уснуло раньше, заканчивается досрочно); `CRASH_TICKS` (600) — для
сцен с разрушением.

## Проверка

```bash
./gradlew :physics-lab:standsCheck -Ponly=xsome/ -Pvary=base -Prepeat=0
```

```bash
./gradlew --no-daemon :physics-lab:stands -Pstart=xsome/square8/rest
```

Тест `CatalogShapesTest` собирает каждую сцену во всех вариациях и ловит тела, поставленные
внахлёст на старте.

## Дополнение 16.11.2026: условие сцены, системы отсчёта, эталон

**Скажи, что в сцене обязано случиться.** Сцена, в которой ничего не произошло, — не зелёная
(правило «сцена не вхолостую»). В конце `setup` объяви условие: `mustTouch(a, b)`,
`mustTouchTerrain(b)`, `mustTouchBalls(b)`, `mustMove(b, связей)`, `mustDeform(b, доля)`,
`mustTurn(b, угол)`, `mustCollide(доля)`, своё `must("…") { … }`; для постановки «рядом, не
касаясь» — `mustNotTouch(a, b)`; для покоя — `idle("почему")`. Без объявления условие берётся
по составу сцены и может оказаться не тем, что ты задумал.

**Сцена не знает, где она стоит в мире.** Проверка симметрии ставит ту же сцену сдвинутой на
тысячу клеток, повёрнутой, отражённой и с клетками в обратном порядке, и итог обязан совпасть.
Поэтому в сцене:

- координаты и скорости — только через мир стенда: `centerOf(b)`, `gameX(p)`, `gameY(p)`,
  `sceneVx(p)`, `setSceneVelocity(p, vx, vy)`, `kick`, `ball`, `rock`, `dragTo`. Не читать
  `world.x[p]` и не писать `world.setVelocity` напрямую: это оси мира, а не сцены;
- клетка фигуры номер `k` — это частица `b.particle(k)`, а не `b.first + k`: при перенумерации
  клетки тела лежат в памяти в другом порядке;
- рамка мира (`arena`) поворачивается только на четверть оборота — сцена с рамкой сама
  проверится так.

Сцена, которая нарушает это, покраснеет на правиле «симметрия», и виновата будет она, а не
физика. Тесты-образцы — `SymmetryTest`: «сцена с зашитой осью мира краснеет», «сцена, зависящая
от номера частицы, краснеет на перенумерации».

**Эталонный решатель шагает ту же сцену.** Он не знает сна, поэтому сцена, которая сама
включает сон (`world.sleepOn = true`), с эталоном не сверяется. Сцена про сон пусть остаётся
как есть; остальным сон трогать незачем — для гидродинамики есть `noSleep()`.

**Проверь, что сцена не «на ребре».** Если итог сцены меняется от сдвига тел на сотые доли
клетки (частица летит точно в стык двух клеток, зубцы двух торцов стоят ровно друг против
друга), её не смогут судить ни симметрия, ни эталон: она попадёт в «хаотичные». Сдвинь тело на
треть клетки вбок — и сцена станет проверкой.
