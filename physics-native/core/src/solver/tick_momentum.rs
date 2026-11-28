//! Учёт импульса за тик — `solver/TickMomentum.kt`. Цели и суммы — в double, частицы — во float (находка 36): типы
//! в каждом выражении — ровно как в Kotlin (Float - Float считается во float и лишь потом расширяется до double).
//! Острова (islandView) не переносятся. Порядок сложения задают куски (PassChunks) и порядок вставки в таблицу
//! куска (GroupSums), а не число воркеров: корень группы — наименьший номер частицы, от порядка слияний не зависит.
//!
//! Отличие устройства от Kotlin: `orgOf` там — ссылка на organismOf из begin; здесь массив организмов передаётся в
//! каждый вызов, которому он нужен (между begin и cancel он не меняется), чтобы не копировать его на каждом тике.

use crate::solver::pass_chunks::PassChunks;
use crate::solver::sleep::BodySleep;

/// Свои части вида учёта острова (`islandView`): список слияний организмов, черновики сумм. Массивы частиц и
/// организмов — общие: остров пишет только свои частицы и организмы, корень группы — наименьшая частица группы, то
/// есть тоже его.
#[derive(Default)]
pub struct MomentumOwn {
    org_linked: Vec<i32>,
    cancel_sums: GroupSums,
    own_chunks: PassChunks,
    own_last: Vec<i32>,
}

impl TickMomentum {
    /// Вид острова поверх побитовой копии учёта мира.
    ///
    /// # Safety
    /// `self` — побитовая копия учёта мира: прежние значения своих полей не освобождаются; копию не освобождать —
    /// только вернуть свои части [remove_island_own].
    pub unsafe fn install_island_own(&mut self, own: &mut MomentumOwn) {
        unsafe {
            std::ptr::write(&mut self.org_linked, std::mem::take(&mut own.org_linked));
            std::ptr::write(&mut self.cancel_sums, std::mem::take(&mut own.cancel_sums));
            std::ptr::write(&mut self.own_chunks, std::mem::take(&mut own.own_chunks));
            std::ptr::write(&mut self.own_last, std::mem::take(&mut own.own_last));
        }
        if self.org_linked.is_empty() {
            self.org_linked = vec![0; 64];
        }
        self.org_linked_n = 0;
    }

    /// # Safety
    /// Только после [install_island_own] на той же копии.
    pub unsafe fn remove_island_own(&mut self, own: &mut MomentumOwn) {
        unsafe {
            own.org_linked = std::ptr::read(&self.org_linked);
            own.cancel_sums = std::ptr::read(&self.cancel_sums);
            own.own_chunks = std::ptr::read(&self.own_chunks);
            own.own_last = std::ptr::read(&self.own_last);
        }
    }

    /// `resetIslandLinks`: новый остров на той же копии — слияний ещё нет.
    pub fn reset_island_links(&mut self) {
        self.org_linked_n = 0;
    }

    /// Деревья групп частиц и организмов — для снимка острова.
    pub fn parent_array(&mut self) -> &mut [i32] {
        &mut self.parent
    }

    pub fn org_parent_array(&mut self) -> &mut [i32] {
        &mut self.org_parent
    }
}

/// `momentumExternalAt`: внешний импульс одной частицы за подшаг. [on_floor] — частица стоит на опоре.
#[inline(always)]
#[allow(clippy::too_many_arguments)]
pub fn momentum_external_at(
    i: usize,
    x: &[f32],
    y: &[f32],
    vx: &[f32],
    vy: &[f32],
    inv_mass: &[f32],
    on_floor: bool,
    before_x: &[f32],
    before_y: &[f32],
    target_x: &mut [f64],
    target_y: &mut [f64],
    target_l: &mut [f64],
    blocked: &mut [bool],
) {
    let w = inv_mass[i];
    if w <= 0.0 {
        if on_floor {
            blocked[i] = true;
        }
        return;
    }
    let m = 1.0f64 / w as f64;
    let fx = m * (vx[i] - before_x[i]) as f64;
    let fy = m * (vy[i] - before_y[i]) as f64;
    target_x[i] += fx;
    target_y[i] += fy;
    target_l[i] += x[i] as f64 * fy - y[i] as f64 * fx;
    if on_floor {
        blocked[i] = true;
    }
}

/// Таблица сумм куска (`TickMomentum.GroupSums`): открытая адресация, обход — в порядке вставки.
#[derive(Clone, Debug)]
pub struct GroupSums {
    keys: Vec<i32>,
    mask: i32,
    used: Vec<i32>,
    n: usize,
    pub values: Vec<f64>,
    last_key: i32,
    last_slot: usize,
}

impl Default for GroupSums {
    fn default() -> Self {
        GroupSums { keys: vec![-1; 256], mask: 255, used: vec![0; 256], n: 0, values: vec![0.0; 256 * 6], last_key: -1, last_slot: 0 }
    }
}

impl GroupSums {
    pub fn clear(&mut self) {
        for k in 0..self.n {
            let u = self.used[k] as usize;
            self.keys[u] = -1;
        }
        self.n = 0;
        self.last_key = -1;
    }

    /// Смещение шести сумм группы [g] в [values].
    #[inline]
    pub fn at(&mut self, g: i32) -> usize {
        if g == self.last_key {
            return self.last_slot;
        }
        let mut h = g.wrapping_mul(-1640531527) & self.mask;
        loop {
            let k = self.keys[h as usize];
            if k == g {
                break;
            }
            if k == -1 {
                if self.n * 2 >= self.keys.len() {
                    self.grow();
                    return self.at(g);
                }
                self.keys[h as usize] = g;
                self.used[self.n] = h;
                self.n += 1;
                let o = h as usize * 6;
                self.values[o..o + 6].fill(0.0);
                break;
            }
            h = (h + 1) & self.mask;
        }
        self.last_key = g;
        self.last_slot = h as usize * 6;
        self.last_slot
    }

    #[cold]
    fn grow(&mut self) {
        let old_keys = std::mem::take(&mut self.keys);
        let old_values = std::mem::take(&mut self.values);
        let old_used = std::mem::take(&mut self.used);
        let old_n = self.n;
        let size = old_keys.len() * 2;
        self.keys = vec![-1; size];
        self.mask = size as i32 - 1;
        self.used = vec![0; size];
        self.values = vec![0.0; size * 6];
        self.n = 0;
        self.last_key = -1;
        for k in 0..old_n {
            let slot = old_used[k] as usize;
            let to = self.at(old_keys[slot]);
            self.values[to..to + 6].copy_from_slice(&old_values[slot * 6..slot * 6 + 6]);
        }
        self.last_key = -1;
    }

    #[inline]
    pub fn count(&self) -> usize {
        self.n
    }
    #[inline]
    pub fn key_at(&self, k: usize) -> i32 {
        self.keys[self.used[k] as usize]
    }
    #[inline]
    pub fn offset_at(&self, k: usize) -> usize {
        self.used[k] as usize * 6
    }
}

/// Число диапазонов учёта: бодрствующие пролёты сна или весь мир одним диапазоном без сна (тесты).
#[inline]
pub fn span_count(sleep: Option<&BodySleep>) -> usize {
    match sleep {
        None => 1,
        Some(s) => s.span_count,
    }
}

/// Диапазон учёта [k].
#[inline]
pub fn span_at(sleep: Option<&BodySleep>, n: usize, k: usize) -> (usize, usize) {
    match sleep {
        None => (0, n),
        Some(s) => (s.span_start[k] as usize, s.span_end[k] as usize),
    }
}

#[derive(Clone, Debug)]
pub struct TickMomentum {
    pub n: usize,
    min_arm2: f64,
    parent: Vec<i32>,
    first: Vec<i32>,
    org_parent: Vec<i32>,
    pub target_x: Vec<f64>,
    pub target_y: Vec<f64>,
    pub target_l: Vec<f64>,
    pub before_x: Vec<f32>,
    pub before_y: Vec<f32>,
    mass: Vec<f64>,
    cx: Vec<f64>,
    cy: Vec<f64>,
    dx: Vec<f64>,
    dy: Vec<f64>,
    dl: Vec<f64>,
    inertia: Vec<f64>,
    pub blocked: Vec<bool>,
    cancel_sums: GroupSums,
    org_linked: Vec<i32>,
    org_linked_n: usize,
    own_chunks: PassChunks,
    own_last: Vec<i32>,
}

impl TickMomentum {
    pub fn new(n: usize, min_spin_arm: f32) -> TickMomentum {
        TickMomentum {
            n,
            min_arm2: min_spin_arm as f64 * min_spin_arm as f64,
            parent: vec![0; n],
            first: vec![0; n],
            org_parent: vec![0; n],
            target_x: vec![0.0; n],
            target_y: vec![0.0; n],
            target_l: vec![0.0; n],
            before_x: vec![0.0; n],
            before_y: vec![0.0; n],
            mass: vec![0.0; n],
            cx: vec![0.0; n],
            cy: vec![0.0; n],
            dx: vec![0.0; n],
            dy: vec![0.0; n],
            dl: vec![0.0; n],
            inertia: vec![0.0; n],
            blocked: vec![false; n],
            cancel_sums: GroupSums::default(),
            org_linked: vec![0; 64],
            org_linked_n: 0,
            own_chunks: PassChunks::default(),
            own_last: Vec::new(),
        }
    }

    fn find(&mut self, i: i32) -> i32 {
        let parent = &mut self.parent;
        let mut r = i;
        while parent[r as usize] != r {
            r = parent[r as usize];
        }
        let mut v = i;
        while parent[v as usize] != r {
            let next = parent[v as usize];
            parent[v as usize] = r;
            v = next;
        }
        r
    }

    fn find_org(&mut self, o: i32) -> i32 {
        let op = &mut self.org_parent;
        let mut r = o;
        while op[r as usize] != r {
            r = op[r as usize];
        }
        let mut v = o;
        while op[v as usize] != r {
            let next = op[v as usize];
            op[v as usize] = r;
            v = next;
        }
        r
    }

    fn union_org(&mut self, a: i32, b: i32) {
        let ra = self.find_org(a);
        let rb = self.find_org(b);
        if ra == rb {
            return;
        }
        let child = if self.first[ra as usize] <= self.first[rb as usize] { rb } else { ra };
        self.org_parent[child as usize] = if child == rb { ra } else { rb };
        // Корнем организм перестаёт быть один раз: присоединяются только корни. См. merge_org_groups.
        if self.org_linked_n == self.org_linked.len() {
            let cap = self.org_linked_n * 2;
            self.org_linked.resize(cap, 0);
        }
        self.org_linked[self.org_linked_n] = child;
        self.org_linked_n += 1;
    }

    fn union_particles(&mut self, i: usize, j: usize, org: &[i32]) {
        let oi = org[i];
        let oj = org[j];
        if oi >= 0 && oj >= 0 && self.first[oi as usize] >= 0 && self.first[oj as usize] >= 0 {
            if oi != oj {
                self.union_org(oi, oj);
            }
        } else {
            self.union(i as i32, j as i32);
        }
    }

    fn union(&mut self, i: i32, j: i32) {
        let a = self.find(i);
        let b = self.find(j);
        if a != b {
            self.parent[a.max(b) as usize] = a.min(b);
        }
    }

    /// [outside] — мёртвые клетки: в группу своего организма не входят (см. Kotlin).
    #[allow(clippy::too_many_arguments)]
    pub fn begin(
        &mut self,
        x: &[f32],
        y: &[f32],
        vx: &[f32],
        vy: &[f32],
        inv_mass: &[f32],
        org: &[i32],
        sleep: Option<&BodySleep>,
        outside: Option<&[bool]>,
    ) {
        self.begin_groups(org, sleep, outside);
        for k in 0..span_count(sleep) {
            let (from, to) = span_at(sleep, self.n, k);
            self.begin_targets(from, to, x, y, vx, vy, inv_mass);
        }
    }

    /// Группы по организмам: общие first и union, в одном потоке.
    pub fn begin_groups(&mut self, org: &[i32], sleep: Option<&BodySleep>, outside: Option<&[bool]>) {
        self.first.fill(-1);
        for (o, p) in self.org_parent.iter_mut().enumerate() {
            *p = o as i32;
        }
        self.org_linked_n = 0;
        let spans = span_count(sleep);
        for k in 0..spans {
            let (from, to) = span_at(sleep, self.n, k);
            for i in from..to {
                self.parent[i] = i as i32;
            }
        }
        for k in 0..spans {
            let (from, to) = span_at(sleep, self.n, k);
            for i in from..to {
                let o = org[i];
                if o >= 0 && outside.is_none_or(|out| !out[i]) {
                    let f = self.first[o as usize];
                    if f < 0 {
                        self.first[o as usize] = i as i32;
                    } else {
                        self.union(f, i as i32);
                    }
                }
            }
        }
    }

    /// Импульс частиц [from, to) в начале тика. Кусок параллельного прохода: у каждой частицы свои поля.
    #[allow(clippy::too_many_arguments)]
    pub fn begin_targets(&mut self, from: usize, to: usize, x: &[f32], y: &[f32], vx: &[f32], vy: &[f32], inv_mass: &[f32]) {
        let (tx, ty, tl, blocked) = (&mut self.target_x, &mut self.target_y, &mut self.target_l, &mut self.blocked);
        for i in from..to {
            let m = if inv_mass[i] > 0.0 { 1.0f64 / inv_mass[i] as f64 } else { 0.0 };
            tx[i] = m * vx[i] as f64;
            ty[i] = m * vy[i] as f64;
            tl[i] = m * (x[i] as f64 * vy[i] as f64 - y[i] as f64 * vx[i] as f64);
            blocked[i] = inv_mass[i] <= 0.0;
        }
    }

    /// Частица проснулась посреди тика: до конца тика блокирует свою группу.
    pub fn admit_frozen(&mut self, i: usize) {
        self.parent[i] = i as i32;
        self.blocked[i] = true;
        self.target_x[i] = 0.0;
        self.target_y[i] = 0.0;
        self.target_l[i] = 0.0;
    }

    /// Частица упёрлась в рамку мира: её группа в этом тике из учёта выходит.
    #[inline]
    pub fn block(&mut self, i: usize) {
        self.blocked[i] = true;
    }

    /// Временная связь объединяет группы концов, как касание.
    pub fn note_joint(&mut self, i: usize, j: usize, inv_mass: &[f32], org: &[i32]) {
        if inv_mass[i] <= 0.0 || inv_mass[j] <= 0.0 {
            self.blocked[i] = true;
            self.blocked[j] = true;
        } else {
            self.union_particles(i, j, org);
        }
    }

    /// `contactKey`: -1 — сливать нечего, -2 — поштучный путь, иначе пара организмов (меньший в старших битах).
    pub fn contact_key(&mut self, i: usize, j: usize, inv_mass: &[f32], org: &[i32]) -> i64 {
        if inv_mass[i] <= 0.0 || inv_mass[j] <= 0.0 {
            self.blocked[i] = true;
            self.blocked[j] = true;
            return -1;
        }
        let oi = org[i];
        let oj = org[j];
        if oi < 0 || oj < 0 || self.first[oi as usize] < 0 || self.first[oj as usize] < 0 {
            return -2;
        }
        if oi == oj {
            return -1;
        }
        if oi < oj {
            ((oi as i64) << 32) | oj as i64
        } else {
            ((oj as i64) << 32) | oi as i64
        }
    }

    /// `contactKey` для кусков воркеров: пометку «касание неподвижной частицы» разные куски могут ставить одной и той же
    /// частице, поэтому — атомарной записью через [blocked] (указатель на `self.blocked`, взятый до стадии).
    ///
    /// # Safety
    /// [blocked] указывает на `self.blocked` длиной не меньше номеров частиц; пока идёт стадия, `blocked` не читается.
    pub unsafe fn contact_key_shared(&self, i: usize, j: usize, inv_mass: &[f32], org: &[i32], blocked: *mut bool) -> i64 {
        use std::sync::atomic::{AtomicBool, Ordering};
        if inv_mass[i] <= 0.0 || inv_mass[j] <= 0.0 {
            unsafe {
                AtomicBool::from_ptr(blocked.add(i)).store(true, Ordering::Relaxed);
                AtomicBool::from_ptr(blocked.add(j)).store(true, Ordering::Relaxed);
            }
            return -1;
        }
        let oi = org[i];
        let oj = org[j];
        if oi < 0 || oj < 0 || self.first[oi as usize] < 0 || self.first[oj as usize] < 0 {
            return -2;
        }
        if oi == oj {
            return -1;
        }
        if oi < oj {
            ((oi as i64) << 32) | oj as i64
        } else {
            ((oj as i64) << 32) | oi as i64
        }
    }

    pub fn union_key(&mut self, key: i64) {
        self.union_org(((key as u64) >> 32) as i32, key as i32);
    }

    pub fn union_contact(&mut self, i: usize, j: usize, org: &[i32]) {
        self.union_particles(i, j, org);
    }

    /// Одна пара касания (`noteContacts` — цикл вызывающего по касаниям в их порядке).
    #[inline]
    pub fn note_contact(&mut self, i: usize, j: usize, inv_mass: &[f32], org: &[i32]) {
        if inv_mass[i] <= 0.0 || inv_mass[j] <= 0.0 {
            self.blocked[i] = true;
            self.blocked[j] = true;
        } else {
            self.union_particles(i, j, org);
        }
    }

    /// Только диапазоны бодрствующих частиц: у спящих скорость ноль и не меняется.
    pub fn before_external(&mut self, vx: &[f32], vy: &[f32], sleep: Option<&BodySleep>) {
        for k in 0..span_count(sleep) {
            let (s, e) = span_at(sleep, self.n, k);
            self.before_x[s..e].copy_from_slice(&vx[s..e]);
            self.before_y[s..e].copy_from_slice(&vy[s..e]);
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn after_external(
        &mut self,
        x: &[f32],
        y: &[f32],
        vx: &[f32],
        vy: &[f32],
        inv_mass: &[f32],
        floor: Option<&[bool]>,
        sleep: Option<&BodySleep>,
    ) {
        for k in 0..span_count(sleep) {
            let (from, to) = span_at(sleep, self.n, k);
            for i in from..to {
                self.external_at(i, x, y, vx, vy, inv_mass, floor);
            }
        }
    }

    /// Снимок скорости одной частицы до внешних стадий (склеенный проход).
    #[inline]
    pub fn before_at(&mut self, i: usize, vx: f32, vy: f32) {
        self.before_x[i] = vx;
        self.before_y[i] = vy;
    }

    #[inline]
    #[allow(clippy::too_many_arguments)]
    pub fn external_at(&mut self, i: usize, x: &[f32], y: &[f32], vx: &[f32], vy: &[f32], inv_mass: &[f32], floor: Option<&[bool]>) {
        momentum_external_at(
            i,
            x,
            y,
            vx,
            vy,
            inv_mass,
            floor.is_some_and(|f| f[i]),
            &self.before_x,
            &self.before_y,
            &mut self.target_x,
            &mut self.target_y,
            &mut self.target_l,
            &mut self.blocked,
        );
    }

    /// Без сна (тесты): куски режутся здесь же по организмам последнего begin ([org] — тот же массив).
    pub fn cancel_own(&mut self, x: &[f32], y: &[f32], vx: &mut [f32], vy: &mut [f32], inv_mass: &[f32], org: &[i32]) {
        if self.own_last.len() != self.n {
            self.own_last = vec![0; self.n];
        }
        let mut chunks = std::mem::take(&mut self.own_chunks);
        chunks.build_whole(self.n, org, &mut self.own_last);
        self.cancel(x, y, vx, vy, inv_mass, None, &chunks);
        self.own_chunks = chunks;
    }

    /// `cancel` мира: [chunks] — его куски (BodyState.passChunks), те же, что у воркеров.
    #[allow(clippy::too_many_arguments)]
    pub fn cancel(
        &mut self,
        x: &[f32],
        y: &[f32],
        vx: &mut [f32],
        vy: &mut [f32],
        inv_mass: &[f32],
        sleep: Option<&BodySleep>,
        chunks: &PassChunks,
    ) {
        let spans = span_count(sleep);
        for k in 0..spans {
            let (from, to) = span_at(sleep, self.n, k);
            self.clear_range(from, to);
        }
        self.flatten_groups(sleep);
        let mut sums = std::mem::take(&mut self.cancel_sums);
        for c in 0..chunks.count {
            sums.clear();
            for p in chunks.part_start[c]..chunks.part_start[c + 1] {
                let p = p as usize;
                self.momentum_range(chunks.part_from[p] as usize, chunks.part_to[p] as usize, x, y, vx, vy, inv_mass, &mut sums);
            }
            self.merge_momentum(&sums);
        }
        for k in 0..spans {
            let (from, to) = span_at(sleep, self.n, k);
            self.centre_range(from, to);
        }
        for c in 0..chunks.count {
            sums.clear();
            for p in chunks.part_start[c]..chunks.part_start[c + 1] {
                let p = p as usize;
                self.inertia_range(chunks.part_from[p] as usize, chunks.part_to[p] as usize, x, y, inv_mass, &mut sums);
            }
            self.merge_inertia(&sums);
        }
        self.cancel_sums = sums;
        for k in 0..spans {
            let (from, to) = span_at(sleep, self.n, k);
            self.spin_range(from, to);
        }
        for k in 0..spans {
            let (from, to) = span_at(sleep, self.n, k);
            self.apply_range(from, to, x, y, vx, vy, inv_mass);
        }
    }

    pub fn clear_range(&mut self, from: usize, to: usize) {
        self.mass[from..to].fill(0.0);
        self.cx[from..to].fill(0.0);
        self.cy[from..to].fill(0.0);
        self.dx[from..to].fill(0.0);
        self.dy[from..to].fill(0.0);
        self.dl[from..to].fill(0.0);
        self.inertia[from..to].fill(0.0);
    }

    /// `momentumRange`: суммы части куска в таблицу куска [sums]; таблицу чистит вызывающий.
    #[allow(clippy::too_many_arguments)]
    pub fn momentum_range(
        &mut self,
        from: usize,
        to: usize,
        x: &[f32],
        y: &[f32],
        vx: &[f32],
        vy: &[f32],
        inv_mass: &[f32],
        sums: &mut GroupSums,
    ) {
        for i in from..to {
            let g = self.parent[i];
            if self.blocked[i] || inv_mass[i] <= 0.0 {
                self.blocked[g as usize] = true;
                continue;
            }
            let m = 1.0f64 / inv_mass[i] as f64;
            let o = sums.at(g);
            let v = &mut sums.values;
            v[o] += m;
            v[o + 1] += m * x[i] as f64;
            v[o + 2] += m * y[i] as f64;
            v[o + 3] += m * vx[i] as f64 - self.target_x[i];
            v[o + 4] += m * vy[i] as f64 - self.target_y[i];
            v[o + 5] += m * (x[i] as f64 * vy[i] as f64 - y[i] as f64 * vx[i] as f64) - self.target_l[i];
        }
    }

    pub fn merge_momentum(&mut self, sums: &GroupSums) {
        let v = &sums.values;
        for k in 0..sums.count() {
            let g = sums.key_at(k) as usize;
            let o = sums.offset_at(k);
            self.mass[g] += v[o];
            self.cx[g] += v[o + 1];
            self.cy[g] += v[o + 2];
            self.dx[g] += v[o + 3];
            self.dy[g] += v[o + 4];
            self.dl[g] += v[o + 5];
        }
    }

    pub fn inertia_range(&mut self, from: usize, to: usize, x: &[f32], y: &[f32], inv_mass: &[f32], sums: &mut GroupSums) {
        for i in from..to {
            if inv_mass[i] <= 0.0 {
                continue;
            }
            let g = self.parent[i];
            let rx = x[i] as f64 - self.cx[g as usize];
            let ry = y[i] as f64 - self.cy[g as usize];
            let o = sums.at(g);
            sums.values[o] += (rx * rx + ry * ry) / inv_mass[i] as f64;
        }
    }

    pub fn merge_inertia(&mut self, sums: &GroupSums) {
        for k in 0..sums.count() {
            let g = sums.key_at(k) as usize;
            self.inertia[g] += sums.values[sums.offset_at(k)];
        }
    }

    /// `flattenRange`: кусок сплющивания без сжатия путей (воркерам).
    pub fn flatten_range(&mut self, from: usize, to: usize) {
        let parent = &mut self.parent;
        for i in from..to {
            let mut r = i as i32;
            while parent[r as usize] != r {
                r = parent[r as usize];
            }
            parent[i] = r;
        }
    }

    /// `mergeOrgGroups`: слияния организмов — в дерево частиц, в порядке слияний.
    pub fn merge_org_groups(&mut self) {
        for x in 0..self.org_linked_n {
            let o = self.org_linked[x];
            let fo = self.first[o as usize];
            let ro = self.find_org(o);
            let fr = self.first[ro as usize];
            self.union(fo, fr);
        }
    }

    /// `flattenGroups`: после этого parent[i] — корень группы.
    pub fn flatten_groups(&mut self, sleep: Option<&BodySleep>) {
        self.merge_org_groups();
        for k in 0..span_count(sleep) {
            let (from, to) = span_at(sleep, self.n, k);
            for i in from..to {
                let r = self.find(i as i32);
                self.parent[i] = r;
            }
        }
    }

    pub fn centre_range(&mut self, from: usize, to: usize) {
        for g in from..to {
            if self.mass[g] > 0.0 {
                self.cx[g] /= self.mass[g];
                self.cy[g] /= self.mass[g];
                self.dl[g] -= self.cx[g] * self.dy[g] - self.cy[g] * self.dx[g];
                self.dx[g] /= self.mass[g];
                self.dy[g] /= self.mass[g];
            }
        }
    }

    /// Вращение — только у группы с радиусом инерции больше SPIN_MIN_ARM.
    pub fn spin_range(&mut self, from: usize, to: usize) {
        for g in from..to {
            self.dl[g] = if self.inertia[g] > self.mass[g] * self.min_arm2 { self.dl[g] / self.inertia[g] } else { 0.0 };
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn apply_range(&mut self, from: usize, to: usize, x: &[f32], y: &[f32], vx: &mut [f32], vy: &mut [f32], inv_mass: &[f32]) {
        for i in from..to {
            let g = self.parent[i] as usize;
            if self.blocked[g] || inv_mass[i] <= 0.0 {
                continue;
            }
            vx[i] = (vx[i] as f64 - (self.dx[g] - self.dl[g] * (y[i] as f64 - self.cy[g]))) as f32;
            vy[i] = (vy[i] as f64 - (self.dy[g] + self.dl[g] * (x[i] as f64 - self.cx[g]))) as f32;
        }
    }
}
