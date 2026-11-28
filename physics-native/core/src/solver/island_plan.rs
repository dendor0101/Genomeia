//! Разбиение мира на острова и их списки — `solver/TickScope.kt` (partitionIslands, области), `IslandRun.kt`
//! (IslandLists, IslandPlan, buildIslandPlan), `solver/IslandChooser.kt` (режим по замеру) на Rust. Широкая фаза
//! острова между тиками (`IslandBroadphase`) не перенесена: копия касаний острова собирает её каждый тик с нуля — биты
//! те же (список пар канонический), цена — около 7% работы островов (профиль Kotlin 25.11).

use crate::solver::blocks::SCOPE_WORLD;
use crate::state::BodyState;

/// Режим островов (`ISLANDS`): выключены, всегда (проверки), по замеру ([IslandChooser]).
pub const ISLANDS_OFF: i32 = 0;
pub const ISLANDS_ON: i32 = 1;
pub const ISLANDS_AUTO: i32 = 2;

/// Наибольший остров, клеток (`ISLAND_MAX_CELLS`): крупнее — в мир, по блокам на всех воркерах.
pub const ISLAND_MAX_CELLS: i32 = 2000;

/// Группа тела ещё не решена (остров или мир).
const GROUP_UNSET: i32 = -3;

/// Черновики разбиения: по телу раскладки (`IslandScratch`).
#[derive(Default)]
pub struct IslandScratch {
    min_x: Vec<f32>,
    min_y: Vec<f32>,
    max_x: Vec<f32>,
    max_y: Vec<f32>,
    cells: Vec<i32>,
    pub blocks: Vec<i32>,
    pub island: Vec<i32>,
    ok: Vec<bool>,
    /// Группы тел с пересекающимися рамками: дерево по телам, корень — наименьшее тело группы.
    parent: Vec<i32>,
    group_cells: Vec<i32>,
    group_ok: Vec<bool>,
    group_runs: Vec<i32>,
    group_island: Vec<i32>,
    keys: Vec<i64>,
}

impl IslandScratch {
    fn ensure_runs(&mut self, runs: usize) {
        if self.min_x.len() >= runs {
            return;
        }
        let cap = runs.max(self.min_x.len() * 2).max(16);
        self.min_x = vec![0.0; cap];
        self.min_y = vec![0.0; cap];
        self.max_x = vec![0.0; cap];
        self.max_y = vec![0.0; cap];
        self.cells = vec![0; cap];
        self.blocks = vec![0; cap];
        self.island = vec![0; cap];
        self.ok = vec![false; cap];
        self.parent = vec![0; cap];
        self.group_cells = vec![0; cap];
        self.group_ok = vec![false; cap];
        self.group_runs = vec![0; cap];
        self.group_island = vec![0; cap];
        self.keys = vec![0; cap];
    }

    fn find(&mut self, r: usize) -> usize {
        let mut x = r;
        while self.parent[x] as usize != x {
            x = self.parent[x] as usize;
        }
        let mut y = r;
        while self.parent[y] as usize != x {
            let next = self.parent[y] as usize;
            self.parent[y] = x as i32;
            y = next;
        }
        x
    }

    fn union(&mut self, a: usize, b: usize) {
        let ra = self.find(a);
        let rb = self.find(b);
        if ra != rb {
            self.parent[ra.max(rb)] = ra.min(rb) as i32;
        }
    }
}

/// Списки по островам подряд (`IslandLists`): остров k — items[start[k]..start[k + 1]]. По счёту ([begin], [count],
/// [seal], [add]) — для списков одним проходом по миру; дописыванием ([begin_append], [push], [close]) — по острову.
#[derive(Default)]
pub struct IslandLists {
    pub start: Vec<i32>,
    pub items: Vec<i32>,
    cursor: Vec<i32>,
    groups: usize,
    length: usize,
}

impl IslandLists {
    #[inline]
    pub fn from(&self, k: usize) -> usize {
        self.start[k] as usize
    }
    #[inline]
    pub fn to(&self, k: usize) -> usize {
        self.start[k + 1] as usize
    }
    /// Список острова [k].
    #[inline]
    pub fn of(&self, k: usize) -> &[i32] {
        &self.items[self.from(k)..self.to(k)]
    }

    fn begin(&mut self, groups: usize) {
        self.groups = groups;
        if self.start.len() < groups + 1 {
            self.start.resize((groups + 1).max(self.start.len() * 2), 0);
        }
        self.start[..groups + 1].fill(0);
        if self.cursor.len() < groups {
            self.cursor.resize(groups.max(self.cursor.len() * 2), 0);
        }
    }

    #[inline]
    fn count(&mut self, k: usize) {
        self.start[k + 1] += 1;
    }

    fn seal(&mut self) {
        for g in 0..self.groups {
            self.start[g + 1] += self.start[g];
        }
        let total = self.start[self.groups] as usize;
        if self.items.len() < total {
            self.items.resize(total.max(self.items.len() * 2), 0);
        }
        self.cursor[..self.groups].copy_from_slice(&self.start[..self.groups]);
    }

    #[inline]
    fn add(&mut self, k: usize, v: i32) {
        self.items[self.cursor[k] as usize] = v;
        self.cursor[k] += 1;
    }

    /// По счёту из готовых областей: элемент x (0..n) — в список острова scopes[x], если он остров.
    fn fill(&mut self, scopes: &[i32], n: usize, groups: usize) {
        self.begin(groups);
        for &k in &scopes[..n] {
            if k >= 0 {
                self.count(k as usize);
            }
        }
        self.seal();
        for (x, &k) in scopes[..n].iter().enumerate() {
            if k >= 0 {
                self.add(k as usize, x as i32);
            }
        }
    }

    fn begin_append(&mut self, groups: usize) {
        self.groups = groups;
        if self.start.len() < groups + 1 {
            self.start.resize((groups + 1).max(self.start.len() * 2), 0);
        }
        self.start[0] = 0;
        self.length = 0;
    }

    fn push(&mut self, v: i32) {
        if self.length == self.items.len() {
            self.items.resize((self.length * 2).max(16), 0);
        }
        self.items[self.length] = v;
        self.length += 1;
    }

    /// [v], если его ещё нет среди дописанных с места [from] (списки там короткие — по одному на тело).
    fn push_unique(&mut self, v: i32, from: usize) {
        if self.items[from..self.length].contains(&v) {
            return;
        }
        self.push(v);
    }

    /// Дописанные с места [from] — по возрастанию (вставками: их единицы).
    fn sort_tail(&mut self, from: usize) {
        for x in from + 1..self.length {
            let v = self.items[x];
            let mut y = x as isize - 1;
            while y >= from as isize && self.items[y as usize] > v {
                self.items[y as usize + 1] = self.items[y as usize];
                y -= 1;
            }
            self.items[(y + 1) as usize] = v;
        }
    }

    fn close(&mut self, k: usize) {
        self.start[k + 1] = self.length as i32;
    }
}

/// Списки островов тика (`IslandPlan`). Собирает [BodyState::build_island_plan] в главном потоке, воркеры только
/// читают.
#[derive(Default)]
pub struct IslandPlan {
    pub count: usize,
    /// Блоки острова по возрастанию номера.
    pub blocks: IslandLists,
    /// Наборы швов острова по возрастанию: по одному на его тело из нескольких блоков.
    pub seams: IslandLists,
    /// Диапазоны частиц острова: from = span_from[x], to = span_to[x], x в spans[k].
    pub spans: IslandLists,
    pub span_from: Vec<i32>,
    pub span_to: Vec<i32>,
    span_n: usize,
    /// Организмы острова (без повторов).
    pub orgs: IslandLists,
    /// Вершины контура острова в порядке списка вершин мира.
    pub verts: IslandLists,
    /// Кости острова: из списков костей его блоков и швов.
    pub bones: IslandLists,
    /// Номера записей плавности мышц и роста мира, по островам, в порядке списков мира.
    pub lerp_edges: IslandLists,
    pub lerp_tris: IslandLists,
    pub grow_edges: IslandLists,
    pub grow_tris: IslandLists,
    /// Частиц острова.
    pub cells: Vec<i32>,
    pub max_cells: usize,
    pub max_orgs: usize,
    /// Острова от больших к меньшим: последний кусок у барьера — самый короткий.
    pub order: Vec<i32>,
    /// Остров откатился (событие) и идёт с миром. Пишет воркер своего острова.
    pub aborted: Vec<bool>,
    order_keys: Vec<i64>,
    org_mark: Vec<i32>,
    org_stamp: i32,
    /// Область каждой записи плавности: [0] мышцы — рёбра, [1] мышцы — треугольники, [2] рост — рёбра,
    /// [3] рост — треугольники.
    entry_scopes: [Vec<i32>; 4],
}

impl IslandPlan {
    fn scope_of(&mut self, list: usize, n: usize) -> &mut Vec<i32> {
        if self.entry_scopes[list].len() < n {
            let cap = n.max(self.entry_scopes[list].len() * 2);
            self.entry_scopes[list].resize(cap, 0);
        }
        &mut self.entry_scopes[list]
    }

    /// Запись [x] списка [list] остаётся миру: её область — мир или откатившийся остров.
    #[inline]
    pub fn world_entry(&self, list: usize, x: usize) -> bool {
        let k = self.entry_scopes[list][x];
        k < 0 || self.aborted[k as usize]
    }

    fn reset(&mut self, count: usize) {
        self.count = count;
        if self.cells.len() < count {
            let cap = count.max(self.cells.len() * 2);
            self.cells.resize(cap, 0);
            self.order.resize(cap, 0);
            self.order_keys.resize(cap, 0);
            self.aborted.resize(cap, false);
        }
        self.cells[..count].fill(0);
        self.aborted[..count].fill(false);
        self.max_cells = 0;
        self.max_orgs = 0;
    }

    fn begin_spans(&mut self) {
        self.spans.begin_append(self.count);
        self.span_n = 0;
    }

    fn add_span(&mut self, from: i32, to: i32) {
        if self.span_n == self.span_from.len() {
            let cap = (self.span_n * 2).max(16);
            self.span_from.resize(cap, 0);
            self.span_to.resize(cap, 0);
        }
        self.span_from[self.span_n] = from;
        self.span_to[self.span_n] = to;
        let x = self.span_n as i32;
        self.spans.push(x);
        self.span_n += 1;
    }

    fn ensure_orgs(&mut self, organism_count: usize) {
        if self.org_mark.len() < organism_count {
            let cap = organism_count.max(self.org_mark.len() * 2);
            self.org_mark = vec![0; cap];
            self.org_stamp = 0;
        }
    }

    /// Пометка организма для списка острова: true — впервые в этом острове.
    fn mark_org(&mut self, o: usize) -> bool {
        if self.org_mark[o] == self.org_stamp {
            return false;
        }
        self.org_mark[o] = self.org_stamp;
        true
    }

    fn next_org_stamp(&mut self) {
        if self.org_stamp == i32::MAX {
            self.org_mark.fill(0);
            self.org_stamp = 0;
        }
        self.org_stamp += 1;
    }

    fn sort_order(&mut self) {
        for k in 0..self.count {
            self.order_keys[k] = (((i32::MAX - self.cells[k]) as i64) << 32) | k as i64;
        }
        self.order_keys[..self.count].sort_unstable();
        for x in 0..self.count {
            self.order[x] = self.order_keys[x] as i32;
        }
    }
}

/// Острова — по замеру времени (`IslandChooser`): острова выигрывают на мире из многих мелких тел и проигрывают на
/// мире с крупными; где граница, зависит от устройства. Выбор не меняет битов (сумма мира с островами и без одна),
/// время решает только, как считать. Цикл: прогрев [WARMUP] тиков блоками по [WARM_BLOCK] без островов и с ними (код
/// обоих путей прогрет), затем проба — четыре блока по [BLOCK] тиков (без, с, без, с; первый тик блока не считается),
/// острова — если медиана их тиков быстрее хотя бы на 3% и острова были во всех тиках пробы; затем [HOLD] тиков в
/// выбранном режиме, тот же ответ подряд — вдвое дольше, до [HOLD_MAX].
#[derive(Default)]
pub struct IslandChooser {
    phase: i32,
    tick: i32,
    off: [i64; SAMPLES],
    on: [i64; SAMPLES],
    off_n: usize,
    on_n: usize,
    on_ticks: i32,
    island_ticks: i32,
    pub chosen: bool,
    hold: i32,
    probed: bool,
}

const WARMUP_PHASE: i32 = 0;
const PROBE_PHASE: i32 = 1;
const HOLD_PHASE: i32 = 2;
const WARMUP: i32 = 120;
const WARM_BLOCK: i32 = 20;
const BLOCK: i32 = 5;
const SAMPLES: usize = 2 * (BLOCK as usize - 1);
const HOLD: i32 = 300;
const HOLD_MAX: i32 = 2400;
const WIN_PERCENT: i64 = 97;

impl IslandChooser {
    /// Острова в этом тике.
    pub fn want_islands(&self) -> bool {
        match self.phase {
            WARMUP_PHASE => (self.tick / WARM_BLOCK) % 2 == 1,
            PROBE_PHASE => (self.tick / BLOCK) % 2 == 1,
            _ => self.chosen,
        }
    }

    /// Тик прошёл за [ns] наносекунд, островов в нём [islands].
    pub fn record(&mut self, ns: i64, islands: usize) {
        match self.phase {
            WARMUP_PHASE => {
                self.tick += 1;
                if self.tick >= WARMUP {
                    self.start_probe();
                }
            }
            PROBE_PHASE => {
                let with_islands = (self.tick / BLOCK) % 2 == 1;
                if with_islands {
                    self.on_ticks += 1;
                    if islands > 0 {
                        self.island_ticks += 1;
                    }
                }
                if self.tick % BLOCK != 0 {
                    if with_islands {
                        self.on[self.on_n] = ns;
                        self.on_n += 1;
                    } else {
                        self.off[self.off_n] = ns;
                        self.off_n += 1;
                    }
                }
                self.tick += 1;
                if self.tick == BLOCK * 4 {
                    self.decide();
                }
            }
            _ => {
                self.tick += 1;
                if self.tick >= self.hold {
                    self.start_probe();
                }
            }
        }
    }

    fn start_probe(&mut self) {
        self.phase = PROBE_PHASE;
        self.tick = 0;
        self.off_n = 0;
        self.on_n = 0;
        self.on_ticks = 0;
        self.island_ticks = 0;
    }

    fn decide(&mut self) {
        let next = self.island_ticks == self.on_ticks && median(&self.on[..self.on_n]) * 100 < median(&self.off[..self.off_n]) * WIN_PERCENT;
        self.hold = if self.probed && next == self.chosen { (self.hold * 2).min(HOLD_MAX) } else { HOLD };
        self.chosen = next;
        self.probed = true;
        self.phase = HOLD_PHASE;
        self.tick = 0;
    }
}

fn median(samples: &[i64]) -> i64 {
    let mut sorted = [0i64; SAMPLES];
    let n = samples.len();
    sorted[..n].copy_from_slice(samples);
    sorted[..n].sort_unstable();
    sorted[n / 2]
}

/// Биты float, упорядоченные как сами числа (`sortableBits`).
#[inline]
fn sortable_bits(f: f32) -> i32 {
    let b = f.to_bits() as i32;
    if b < 0 { b ^ 0x7fff_ffff } else { b }
}

/// `unionNearRuns`: тела, чьи рамки с запасом [pad] пересекаются, — в одну группу. Сортировка по началу рамки вдоль
/// оси, где тела разбросаны шире, и проход вперёд, пока следующая рамка начинается до конца текущей.
fn union_near_runs(isl: &mut IslandScratch, runs: usize, pad: f32) {
    let (mut lo_x, mut hi_x) = (f32::INFINITY, f32::NEG_INFINITY);
    let (mut lo_y, mut hi_y) = (f32::INFINITY, f32::NEG_INFINITY);
    for r in 0..runs {
        if isl.cells[r] == 0 {
            continue;
        }
        if isl.min_x[r] < lo_x {
            lo_x = isl.min_x[r];
        }
        if isl.max_x[r] > hi_x {
            hi_x = isl.max_x[r];
        }
        if isl.min_y[r] < lo_y {
            lo_y = isl.min_y[r];
        }
        if isl.max_y[r] > hi_y {
            hi_y = isl.max_y[r];
        }
    }
    let along_x = hi_x - lo_x >= hi_y - lo_y;
    let mut m = 0usize;
    for r in 0..runs {
        if isl.cells[r] > 0 {
            let a = if along_x { isl.min_x[r] } else { isl.min_y[r] };
            isl.keys[m] = ((sortable_bits(a) as i64) << 32) | r as i64;
            m += 1;
        }
    }
    isl.keys[..m].sort_unstable();
    for a in 0..m {
        let r = isl.keys[a] as i32 as usize;
        let (a_max_r, b_min_r, b_max_r) =
            if along_x { (isl.max_x[r], isl.min_y[r], isl.max_y[r]) } else { (isl.max_y[r], isl.min_x[r], isl.max_x[r]) };
        let end = a_max_r + pad;
        for b in a + 1..m {
            let q = isl.keys[b] as i32 as usize;
            let (a_min_q, b_min_q, b_max_q) =
                if along_x { (isl.min_x[q], isl.min_y[q], isl.max_y[q]) } else { (isl.min_y[q], isl.min_x[q], isl.max_x[q]) };
            if a_min_q - pad > end {
                break;
            }
            if b_min_r - pad <= b_max_q + pad && b_min_q - pad <= b_max_r + pad {
                isl.union(r, q);
            }
        }
    }
}

impl BodyState {
    /// `partitionIslands`: острова на этот тик. Единица — тело раскладки (связный кусок запечённого графа). Остров —
    /// группа тел, чьи рамки с запасом пересекаются, а с остальными — нет; запас — ход за тик на потолке скорости и два
    /// наибольших радиуса касания, всё вдвое. В остров не идёт группа с телом, у которого спящие или неподвижные
    /// частицы, под мышью или с липучкой, и группа крупнее [ISLAND_MAX_CELLS]. Возвращает число островов; 0 —
    /// островов нет, области сброшены.
    pub fn partition_islands(&mut self) -> usize {
        self.clear_islands();
        if self.constraint_blocks.is_none() || !self.isl.on {
            return 0;
        }
        // Несобранный контур первый же refreshSurface подшага собрал бы в тени — общие списки.
        if self.baked.dirty_count != 0 {
            return 0;
        }
        let runs = self.constraint_blocks.as_ref().unwrap().body_runs;
        if runs < 2 {
            return 0;
        }
        let mut isl = std::mem::take(&mut self.isl.scratch);
        isl.ensure_runs(runs);
        for r in 0..runs {
            isl.min_x[r] = f32::INFINITY;
            isl.min_y[r] = f32::INFINITY;
            isl.max_x[r] = f32::NEG_INFINITY;
            isl.max_y[r] = f32::NEG_INFINITY;
            isl.cells[r] = 0;
            isl.ok[r] = true;
            isl.blocks[r] = 0;
            isl.parent[r] = r as i32;
        }
        {
            let layout = self.constraint_blocks.as_ref().unwrap();
            for b in 0..layout.block_count {
                let r = layout.body_run_of[b] as usize;
                isl.blocks[r] += 1;
                for i in layout.first_particle[b] as usize..layout.first_particle[b + 1] as usize {
                    if self.cell_dead[i] {
                        continue;
                    }
                    let (x, y) = (self.px[i], self.py[i]);
                    if x < isl.min_x[r] {
                        isl.min_x[r] = x;
                    }
                    if x > isl.max_x[r] {
                        isl.max_x[r] = x;
                    }
                    if y < isl.min_y[r] {
                        isl.min_y[r] = y;
                    }
                    if y > isl.max_y[r] {
                        isl.max_y[r] = y;
                    }
                    isl.cells[r] += 1;
                    if self.inv_mass[i] <= 0.0 || self.sleep.is_asleep(self.organism_of[i]) {
                        isl.ok[r] = false;
                    }
                }
            }
            let run_of_particle = |i: i32| layout.body_run_of[layout.particle_block[i as usize] as usize] as usize;
            if self.drag_id >= 0 {
                isl.ok[run_of_particle(self.drag_id)] = false;
            }
            for k in 0..self.joint_active_n {
                let j = self.joint_active[k] as usize;
                isl.ok[run_of_particle(self.joint_a[j])] = false;
                isl.ok[run_of_particle(self.joint_b[j])] = false;
            }
        }
        // Запас на сторону: ход за тик на потолке скорости, радиус касания и запас широкой фазы — с запасом вдвое.
        let max_r = self.contacts.as_ref().map_or(0.0, |ct| ct.max_contact_radius_now());
        let pad = 2.0f32 * (crate::params::MAX_SPEED_CELLS_PER_TICK * self.link_scale + 2.0f32 * max_r);
        union_near_runs(&mut isl, runs, pad);
        for r in 0..runs {
            isl.group_cells[r] = 0;
            isl.group_ok[r] = true;
            isl.group_runs[r] = 0;
            isl.group_island[r] = GROUP_UNSET;
        }
        for r in 0..runs {
            if isl.cells[r] == 0 {
                continue;
            }
            let g = isl.find(r);
            isl.group_cells[g] += isl.cells[r];
            isl.group_runs[g] += 1;
            if !isl.ok[r] {
                isl.group_ok[g] = false;
            }
        }
        let mut count = 0usize;
        for r in 0..runs {
            if isl.cells[r] == 0 {
                isl.island[r] = SCOPE_WORLD;
                continue;
            }
            let g = isl.find(r);
            if isl.group_island[g] == GROUP_UNSET {
                isl.group_island[g] = if isl.group_ok[g] && isl.group_cells[g] <= ISLAND_MAX_CELLS {
                    count += 1;
                    (count - 1) as i32
                } else {
                    SCOPE_WORLD
                };
            }
            isl.island[r] = isl.group_island[g];
        }
        if count == 0 {
            self.isl.scratch = isl;
            return 0;
        }
        // Области: блоки и швы тела, его организмы и частицы.
        if self.isl.org_scope.len() < self.organism_count {
            let cap = self.organism_count.max(self.isl.org_scope.len() * 2);
            self.isl.org_scope.resize(cap, SCOPE_WORLD);
        }
        if self.isl.vertex_scope.len() < self.n {
            let cap = self.n.max(self.isl.vertex_scope.len() * 2);
            self.isl.vertex_scope.resize(cap, SCOPE_WORLD);
        }
        {
            let layout = self.constraint_blocks.as_mut().unwrap();
            for b in 0..layout.block_count {
                let r = layout.body_run_of[b] as usize;
                let k = isl.island[r];
                if k < 0 {
                    continue;
                }
                layout.bucket_scope[b] = k;
                // Свой набор швов — только у тела из нескольких блоков (общий первый набор одноблочных тел пуст).
                if isl.blocks[r] > 1 {
                    let s = layout.seam_bucket[b] as usize;
                    layout.bucket_scope[s] = k;
                }
                for i in layout.first_particle[b] as usize..layout.first_particle[b + 1] as usize {
                    self.isl.vertex_scope[i] = k;
                    self.isl.org_scope[self.organism_of[i] as usize] = k;
                }
            }
        }
        self.sleep.org_scope_on = true;
        if let Some(ct) = self.contacts.as_mut() {
            ct.vertex_scope_on = true;
        }
        self.isl.count = count;
        self.build_island_plan(&isl, count);
        self.isl.scratch = isl;
        self.prepare_shared_for_islands();
        count
    }

    /// `buildIslandPlan`: списки островов — блоки, швы, диапазоны, организмы, вершины, кости, записи плавности; порядок
    /// раздачи. Один проход по блокам, частицам островов, вершинам контура и спискам плавности — O(мира) за тик.
    fn build_island_plan(&mut self, isl: &IslandScratch, count: usize) {
        let mut plan = std::mem::take(&mut self.isl.plan);
        plan.reset(count);
        let layout = self.constraint_blocks.as_ref().unwrap();
        let fp = &layout.first_particle;
        plan.blocks.begin(count);
        for b in 0..layout.block_count {
            let k = isl.island[layout.body_run_of[b] as usize];
            if k >= 0 {
                plan.blocks.count(k as usize);
            }
        }
        plan.blocks.seal();
        for b in 0..layout.block_count {
            let k = isl.island[layout.body_run_of[b] as usize];
            if k < 0 {
                continue;
            }
            plan.blocks.add(k as usize, b as i32);
            plan.cells[k as usize] += fp[b + 1] - fp[b];
        }
        // Диапазоны (блоки подряд — один диапазон), организмы, швы (свой у каждого тела острова из нескольких блоков),
        // кости.
        plan.begin_spans();
        plan.ensure_orgs(self.organism_count);
        plan.orgs.begin_append(count);
        plan.seams.begin_append(count);
        plan.bones.begin_append(count);
        for k in 0..count {
            let (mut open, mut end) = (-1i32, -1i32);
            plan.next_org_stamp();
            let mut orgs = 0usize;
            let seam_from = plan.seams.length;
            for x in plan.blocks.from(k)..plan.blocks.to(k) {
                let b = plan.blocks.items[x] as usize;
                let (a, z) = (fp[b], fp[b + 1]);
                if open >= 0 && a == end {
                    end = z;
                } else {
                    if open >= 0 {
                        plan.add_span(open, end);
                    }
                    open = a;
                    end = z;
                }
                for i in a as usize..z as usize {
                    let o = self.organism_of[i] as usize;
                    if plan.mark_org(o) {
                        plan.orgs.push(o as i32);
                        orgs += 1;
                    }
                }
                let mut bone = layout.bones.head[b];
                while bone >= 0 {
                    plan.bones.push(bone);
                    bone = layout.bones.next[bone as usize];
                }
                if isl.blocks[layout.body_run_of[b] as usize] > 1 {
                    plan.seams.push_unique(layout.seam_bucket[b], seam_from);
                }
            }
            if open >= 0 {
                plan.add_span(open, end);
            }
            plan.seams.sort_tail(seam_from);
            for x in seam_from..plan.seams.length {
                let mut bone = layout.bones.head[plan.seams.items[x] as usize];
                while bone >= 0 {
                    plan.bones.push(bone);
                    bone = layout.bones.next[bone as usize];
                }
            }
            plan.spans.close(k);
            plan.orgs.close(k);
            plan.seams.close(k);
            plan.bones.close(k);
            if plan.cells[k] as usize > plan.max_cells {
                plan.max_cells = plan.cells[k] as usize;
            }
            if orgs > plan.max_orgs {
                plan.max_orgs = orgs;
            }
        }
        // Вершины контура — в порядке списка мира: от него зависит порядок сложения в CCD.
        let vs = &self.isl.vertex_scope;
        plan.verts.begin(count);
        if let Some(ct) = self.contacts.as_ref() {
            for a in 0..ct.vert_count {
                let k = vs[ct.verts[a] as usize];
                if k >= 0 {
                    plan.verts.count(k as usize);
                }
            }
            plan.verts.seal();
            for a in 0..ct.vert_count {
                let v = ct.verts[a];
                let k = vs[v as usize];
                if k >= 0 {
                    plan.verts.add(k as usize, v);
                }
            }
        } else {
            plan.verts.seal();
        }
        // Записи плавности мышц и роста — номера в списках мира, в их порядке; остров записи — по частице.
        let slot = &layout.triangle_slot;
        let n0 = self.lerp_edge_n;
        {
            let es = plan.scope_of(0, n0);
            for x in 0..n0 {
                es[x] = vs[self.baked.edge_u(self.lerp_edge_ids[x] as usize) as usize];
            }
        }
        let es = std::mem::take(&mut plan.entry_scopes[0]);
        plan.lerp_edges.fill(&es, n0, count);
        plan.entry_scopes[0] = es;
        let n1 = self.lerp_tri_n;
        {
            let ts = plan.scope_of(1, n1);
            for x in 0..n1 {
                ts[x] = tri_scope(slot, self.lerp_tri_ids[x] as usize, false, self.tri_count, &self.baked.tri_alive, &self.tri_a, vs);
            }
        }
        let ts = std::mem::take(&mut plan.entry_scopes[1]);
        plan.lerp_tris.fill(&ts, n1, count);
        plan.entry_scopes[1] = ts;
        let n2 = self.grow_lerp_n;
        {
            let gs = plan.scope_of(2, n2);
            for x in 0..n2 {
                let e = self.grow_lerp_edge[x] as usize;
                gs[x] = if self.baked.edge_dead[e] { SCOPE_WORLD } else { vs[self.baked.edge_u(e) as usize] };
            }
        }
        let gs = std::mem::take(&mut plan.entry_scopes[2]);
        plan.grow_edges.fill(&gs, n2, count);
        plan.entry_scopes[2] = gs;
        let n3 = self.grow_lerp_tri_n;
        {
            let gts = plan.scope_of(3, n3);
            for x in 0..n3 {
                gts[x] = tri_scope(slot, self.grow_lerp_tri[x] as usize, true, self.tri_count, &self.baked.tri_alive, &self.tri_a, vs);
            }
        }
        let gts = std::mem::take(&mut plan.entry_scopes[3]);
        plan.grow_tris.fill(&gts, n3, count);
        plan.entry_scopes[3] = gts;
        plan.sort_order();
        self.isl.plan = plan;
    }

    /// `prepareSharedForIslands`: всё, что подшаг дозаказывает или собирает лениво в общих объектах, — заранее, в
    /// главном потоке: куски мира, бодрствующие диапазоны, массивы касаний скоростей и удаления от места покоя,
    /// множители мышц, счёт хваток, массивы касаний на вершину.
    fn prepare_shared_for_islands(&mut self) {
        let n = self.n;
        self.sleep.rebuild_all_spans_if_needed(n, &self.organism_of);
        let (sv, slv) = (self.structure_version, self.sleep.version);
        if !self.pass_chunk_cache.valid(sv, slv, n) {
            let s = &self.sleep;
            self.pass_chunk_cache.build(s.all_span_count, &s.all_span_start, &s.all_span_end, &self.organism_of, &s.last);
            self.pass_chunk_cache.mark_built(sv, slv, n);
        }
        if self.vel_touch_stamp.len() < n {
            self.vel_touch_stamp = vec![0; n];
            self.vel_touch_list = vec![0; n];
        }
        if self.vel_bone_stamp.len() < self.rigid_bones.len() {
            self.vel_bone_stamp = vec![0; self.rigid_bones.len()];
        }
        if self.org_sleep_drift2.len() < n {
            self.org_sleep_drift2 = vec![0.0; n];
            self.sleep_anchor_x = vec![0.0; n];
            self.sleep_anchor_y = vec![0.0; n];
        }
        let m_n = self.muscle_activation.len();
        if self.muscle_scale_of.len() != m_n {
            self.muscle_scale_of = vec![0.0; m_n];
        }
        if self.muscle_activation_prev.len() != m_n {
            self.muscle_activation_prev = vec![0.0; m_n];
        }
        self.grip_count_now();
        let bones = self.rigid_bones.len();
        if let Some(ct) = self.contacts.as_mut() {
            ct.prepare_islands(bones);
        }
    }

    /// `clearIslands`: области прошлого тика — снова мира.
    pub fn clear_islands(&mut self) {
        if self.isl.count > 0 {
            if let Some(layout) = self.constraint_blocks.as_mut() {
                layout.bucket_scope.fill(SCOPE_WORLD);
            }
            self.isl.org_scope.fill(SCOPE_WORLD);
            self.isl.vertex_scope.fill(SCOPE_WORLD);
        }
        if self.isl.count > 0 || self.sleep.org_scope_on {
            self.sleep.org_scope_on = false;
            if let Some(ct) = self.contacts.as_mut() {
                ct.vertex_scope_on = false;
                // Широкая фаза мира — снова по всем вершинам.
                ct.set_scope(SCOPE_WORLD);
            }
        }
        self.isl.count = 0;
        self.isl.scope_particles = -1;
        self.set_tick_scope(SCOPE_WORLD);
    }

    /// `enterWorldScope`: подшаги мира после островов — область мира, диапазоны частиц и широкая фаза заново, даже если
    /// номер области тот же (состав мира сменился).
    pub fn enter_world_scope(&mut self) {
        if let Some(layout) = self.constraint_blocks.as_mut() {
            layout.scope = SCOPE_WORLD;
        }
        self.sleep.force_scope(SCOPE_WORLD);
        if let Some(ct) = self.contacts.as_mut() {
            ct.set_scope(SCOPE_WORLD);
        }
    }

    /// `setTickScope`: [sc] — текущая область стадий (наборы, диапазоны частиц, вершины касаний).
    pub fn set_tick_scope(&mut self, sc: i32) {
        if let Some(layout) = self.constraint_blocks.as_mut() {
            layout.scope = sc;
        }
        self.sleep.set_scope(sc);
        if let Some(ct) = self.contacts.as_mut() {
            if ct.scope != sc {
                ct.set_scope(sc);
            }
        }
    }
}

/// `triScope`: остров треугольника с постоянным номером [id] (живого — [alive]), [SCOPE_WORLD] — мир или мёртвый.
fn tri_scope(slot: &[i32], id: usize, alive: bool, tri_count: usize, tri_alive: &[bool], tri_a: &[i32], vs: &[i32]) -> i32 {
    let w = slot[id];
    if w < 0 || w as usize >= tri_count {
        return SCOPE_WORLD;
    }
    if alive && !tri_alive[id] {
        return SCOPE_WORLD;
    }
    vs[tri_a[w as usize] as usize]
}
