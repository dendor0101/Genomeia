//! Вспомогательные структуры касаний: `contacts/AnchoredContactGrid.kt`, `SweptContactGrid.kt`,
//! `ContactLambdaCache.kt`, `ContactPassCache.kt`, `ContactRadiusMinimum.kt`, `ContactDamageCandidates.kt`,
//! `RigidContactBatch.kt`, а также буферы из `ParallelContacts.kt` (PairKeyBuffer, PierceBuffer) и
//! `ParallelContactSolve.kt` (ContactSolveSink). Хэши — те же умножения с переполнением, что в Kotlin (Long * Long).

use crate::jmath::{minf, strict_cos, strict_sin};

pub(crate) const GOLDEN: i64 = -0x61c8864680b583ebi64;
const MIX: i64 = -7046029254386353131i64;

/// Подвижная сетка по привязкам вершин (`AnchoredContactGrid`): ячейка — цепочка записей, у вершины — список своих.
#[derive(Clone, Debug)]
pub struct AnchoredContactGrid {
    keys: Vec<i64>,
    used: Vec<bool>,
    heads: Vec<i32>,
    mask: i32,
    occupied: usize,
    ent_vert: Vec<i32>,
    ent_cell: Vec<i32>,
    ent_next: Vec<i32>,
    ent_prev: Vec<i32>,
    ent_own_next: Vec<i32>,
    ent_count: usize,
    free_head: i32,
    pub live: usize,
    foot_head: Vec<i32>,
}

impl AnchoredContactGrid {
    pub fn new(n: usize) -> AnchoredContactGrid {
        let capacity = 1024usize;
        AnchoredContactGrid {
            keys: vec![0; capacity],
            used: vec![false; capacity],
            heads: vec![0; capacity],
            mask: capacity as i32 - 1,
            occupied: 0,
            ent_vert: vec![0; capacity],
            ent_cell: vec![0; capacity],
            ent_next: vec![0; capacity],
            ent_prev: vec![0; capacity],
            ent_own_next: vec![0; capacity],
            ent_count: 0,
            free_head: -1,
            live: 0,
            foot_head: vec![-1; n],
        }
    }

    #[inline]
    pub fn ids(&self) -> &[i32] {
        &self.ent_vert
    }
    #[inline]
    pub fn next(&self) -> &[i32] {
        &self.ent_next
    }

    /// `liveEntries`: все записи (вершина, ключ ячейки) — в конец [out_v]/[out_k], для перераскладки по частям
    /// (BoundaryContacts.reshard): набор записей тот же.
    pub fn live_entries(&self, out_v: &mut Vec<i32>, out_k: &mut Vec<i64>) {
        for s in 0..self.keys.len() {
            if !self.used[s] {
                continue;
            }
            let mut e = self.heads[s];
            while e >= 0 {
                out_v.push(self.ent_vert[e as usize]);
                out_k.push(self.keys[s]);
                e = self.ent_next[e as usize];
            }
        }
    }

    pub fn clear(&mut self) {
        self.used.fill(false);
        self.occupied = 0;
        for e in 0..self.ent_count {
            let v = self.ent_vert[e] as usize;
            self.foot_head[v] = -1;
        }
        self.ent_count = 0;
        self.free_head = -1;
        self.live = 0;
    }

    #[inline]
    fn home(&self, key: i64) -> i32 {
        ((key.wrapping_mul(GOLDEN) as u64 >> 40) as i32) & self.mask
    }

    #[inline]
    fn slot(&self, key: i64) -> usize {
        let mut s = self.home(key);
        while self.used[s as usize] && self.keys[s as usize] != key {
            s = (s + 1) & self.mask;
        }
        s as usize
    }

    #[inline]
    pub fn first(&self, key: i64) -> i32 {
        let s = self.slot(key);
        if self.used[s] {
            self.heads[s]
        } else {
            -1
        }
    }

    pub fn insert(&mut self, v: i32, key: i64) {
        let mut s = self.slot(key);
        if !self.used[s] {
            if self.occupied >= self.keys.len() / 2 {
                self.grow();
                s = self.slot(key);
            }
            self.used[s] = true;
            self.keys[s] = key;
            self.heads[s] = -1;
            self.occupied += 1;
        }
        let e = self.take_entry();
        self.ent_vert[e] = v;
        self.ent_cell[e] = s as i32;
        self.ent_prev[e] = -1;
        self.ent_next[e] = self.heads[s];
        if self.heads[s] >= 0 {
            let h = self.heads[s] as usize;
            self.ent_prev[h] = e as i32;
        }
        self.heads[s] = e as i32;
        let f = v as usize;
        self.ent_own_next[e] = self.foot_head[f];
        self.foot_head[f] = e as i32;
        self.live += 1;
    }

    pub fn remove(&mut self, v: i32) {
        let f = v as usize;
        let mut e = self.foot_head[f];
        while e >= 0 {
            let eu = e as usize;
            let own = self.ent_own_next[eu];
            let s = self.ent_cell[eu] as usize;
            if self.ent_prev[eu] >= 0 {
                let p = self.ent_prev[eu] as usize;
                self.ent_next[p] = self.ent_next[eu];
            } else {
                self.heads[s] = self.ent_next[eu];
            }
            if self.ent_next[eu] >= 0 {
                let nx = self.ent_next[eu] as usize;
                self.ent_prev[nx] = self.ent_prev[eu];
            }
            self.ent_own_next[eu] = self.free_head;
            self.free_head = e;
            self.live -= 1;
            if self.heads[s] < 0 {
                self.delete_cell(s);
            }
            e = own;
        }
        self.foot_head[f] = -1;
    }

    fn delete_cell(&mut self, start: usize) {
        let mut i = start as i32;
        self.used[i as usize] = false;
        self.occupied -= 1;
        let mut j = i;
        loop {
            j = (j + 1) & self.mask;
            if !self.used[j as usize] {
                return;
            }
            let h = self.home(self.keys[j as usize]);
            let stays = if i <= j { h > i && h <= j } else { h > i || h <= j };
            if stays {
                continue;
            }
            let (iu, ju) = (i as usize, j as usize);
            self.keys[iu] = self.keys[ju];
            self.heads[iu] = self.heads[ju];
            self.used[iu] = true;
            let mut e = self.heads[iu];
            while e >= 0 {
                self.ent_cell[e as usize] = i;
                e = self.ent_next[e as usize];
            }
            self.used[ju] = false;
            i = j;
        }
    }

    fn take_entry(&mut self) -> usize {
        if self.free_head >= 0 {
            let e = self.free_head as usize;
            self.free_head = self.ent_own_next[e];
            return e;
        }
        if self.ent_count == self.ent_vert.len() {
            let cap = self.ent_count * 2;
            self.ent_vert.resize(cap, 0);
            self.ent_cell.resize(cap, 0);
            self.ent_next.resize(cap, 0);
            self.ent_prev.resize(cap, 0);
            self.ent_own_next.resize(cap, 0);
        }
        let e = self.ent_count;
        self.ent_count += 1;
        e
    }

    fn grow(&mut self) {
        let old_keys = std::mem::take(&mut self.keys);
        let old_used = std::mem::take(&mut self.used);
        let old_heads = std::mem::take(&mut self.heads);
        let size = old_keys.len() << 1;
        self.keys = vec![0; size];
        self.used = vec![false; size];
        self.heads = vec![0; size];
        self.mask = size as i32 - 1;
        for i in 0..old_keys.len() {
            if old_used[i] {
                let s = self.slot(old_keys[i]);
                self.used[s] = true;
                self.keys[s] = old_keys[i];
                self.heads[s] = old_heads[i];
                let mut e = self.heads[s];
                while e >= 0 {
                    self.ent_cell[e as usize] = s as i32;
                    e = self.ent_next[e as usize];
                }
            }
        }
    }
}

/// Неподвижная сетка с метками (`SweptContactGrid`): новая запись — в хвост цепочки ячейки.
#[derive(Clone, Debug)]
pub struct SweptContactGrid {
    keys: Vec<i64>,
    stamps: Vec<i32>,
    heads: Vec<i32>,
    tails: Vec<i32>,
    mask: i32,
    stamp: i32,
    occupied: usize,
    entries: usize,
    pub ids: Vec<i32>,
    pub next: Vec<i32>,
}

impl Default for SweptContactGrid {
    fn default() -> Self {
        SweptContactGrid {
            keys: vec![0; 64],
            stamps: vec![0; 64],
            heads: vec![0; 64],
            tails: vec![0; 64],
            mask: 63,
            stamp: 0,
            occupied: 0,
            entries: 0,
            ids: vec![0; 4096],
            next: vec![0; 4096],
        }
    }
}

impl SweptContactGrid {
    pub fn begin(&mut self, vertices: usize) {
        let need = (vertices * 2).max(self.entries) * 4;
        let mut size = self.keys.len();
        while size < need {
            size <<= 1;
        }
        if size != self.keys.len() {
            self.keys = vec![0; size];
            self.stamps = vec![0; size];
            self.heads = vec![0; size];
            self.tails = vec![0; size];
            self.mask = size as i32 - 1;
            self.stamp = 0;
        }
        if self.stamp == i32::MAX {
            self.stamps.fill(0);
            self.stamp = 0;
        }
        self.stamp += 1;
        self.occupied = 0;
        self.entries = 0;
    }

    #[inline]
    fn slot(&self, key: i64) -> usize {
        let mut s = ((key.wrapping_mul(GOLDEN) as u64 >> 40) as i32) & self.mask;
        while self.stamps[s as usize] == self.stamp && self.keys[s as usize] != key {
            s = (s + 1) & self.mask;
        }
        s as usize
    }

    fn grow(&mut self) {
        let old_keys = std::mem::take(&mut self.keys);
        let old_stamps = std::mem::take(&mut self.stamps);
        let old_heads = std::mem::take(&mut self.heads);
        let old_tails = std::mem::take(&mut self.tails);
        let size = old_keys.len() << 1;
        self.keys = vec![0; size];
        self.stamps = vec![0; size];
        self.heads = vec![0; size];
        self.tails = vec![0; size];
        self.mask = size as i32 - 1;
        for i in 0..old_keys.len() {
            if old_stamps[i] == self.stamp {
                let s = self.slot(old_keys[i]);
                self.keys[s] = old_keys[i];
                self.stamps[s] = self.stamp;
                self.heads[s] = old_heads[i];
                self.tails[s] = old_tails[i];
            }
        }
    }

    pub fn insert(&mut self, key: i64, id: i32) {
        let mut s = self.slot(key);
        if self.stamps[s] != self.stamp {
            if self.occupied >= self.keys.len() / 2 {
                self.grow();
                s = self.slot(key);
            }
            self.occupied += 1;
            self.stamps[s] = self.stamp;
            self.keys[s] = key;
            self.heads[s] = self.entries as i32;
        } else {
            let t = self.tails[s] as usize;
            self.next[t] = self.entries as i32;
        }
        if self.entries == self.next.len() {
            let cap = self.entries * 2;
            self.next.resize(cap, 0);
            self.ids.resize(cap, 0);
        }
        self.ids[self.entries] = id;
        self.next[self.entries] = -1;
        self.tails[s] = self.entries as i32;
        self.entries += 1;
    }

    #[inline]
    pub fn first(&self, key: i64) -> i32 {
        let s = self.slot(key);
        if self.stamps[s] == self.stamp {
            self.heads[s]
        } else {
            -1
        }
    }
}

/// Перенесённые множители касаний по паре (`ContactLambdaCache`): открытая адресация с удалением сдвигом.
#[derive(Clone, Debug)]
pub struct ContactLambdaCache {
    keys: Vec<i64>,
    values: Vec<f32>,
    used: Vec<bool>,
    mask: i32,
    size: usize,
}

impl Default for ContactLambdaCache {
    fn default() -> Self {
        ContactLambdaCache { keys: vec![0; 16], values: vec![0.0; 16], used: vec![false; 16], mask: 15, size: 0 }
    }
}

impl ContactLambdaCache {
    #[inline]
    fn home(&self, key: i64) -> i32 {
        ((key.wrapping_mul(MIX) as u64 >> 32) as i32) & self.mask
    }

    pub fn get_or_default(&self, key: i64, fallback: f32) -> f32 {
        let mut s = self.home(key);
        while self.used[s as usize] {
            if self.keys[s as usize] == key {
                return self.values[s as usize];
            }
            s = (s + 1) & self.mask;
        }
        fallback
    }

    pub fn put(&mut self, key: i64, value: f32) {
        let mut s = self.home(key);
        while self.used[s as usize] {
            if self.keys[s as usize] == key {
                self.values[s as usize] = value;
                return;
            }
            s = (s + 1) & self.mask;
        }
        if self.size * 2 >= self.keys.len() {
            self.grow();
            s = self.home(key);
            while self.used[s as usize] {
                s = (s + 1) & self.mask;
            }
        }
        let su = s as usize;
        self.used[su] = true;
        self.keys[su] = key;
        self.values[su] = value;
        self.size += 1;
    }

    pub fn remove(&mut self, key: i64) {
        let mut hole = self.home(key);
        while self.used[hole as usize] && self.keys[hole as usize] != key {
            hole = (hole + 1) & self.mask;
        }
        if !self.used[hole as usize] {
            return;
        }
        self.size -= 1;
        let mut next = (hole + 1) & self.mask;
        while self.used[next as usize] {
            let first = self.home(self.keys[next as usize]);
            if ((next - first) & self.mask) >= ((next - hole) & self.mask) {
                self.keys[hole as usize] = self.keys[next as usize];
                self.values[hole as usize] = self.values[next as usize];
                hole = next;
            }
            next = (next + 1) & self.mask;
        }
        self.used[hole as usize] = false;
    }

    pub fn clear(&mut self) {
        if self.size == 0 {
            return;
        }
        self.used.fill(false);
        self.size = 0;
    }

    fn grow(&mut self) {
        let old_keys = std::mem::take(&mut self.keys);
        let old_values = std::mem::take(&mut self.values);
        let old_used = std::mem::take(&mut self.used);
        let size = old_keys.len() * 2;
        self.keys = vec![0; size];
        self.values = vec![0.0; size];
        self.used = vec![false; size];
        self.mask = size as i32 - 1;
        self.size = 0;
        for i in 0..old_keys.len() {
            if old_used[i] {
                self.put(old_keys[i], old_values[i]);
            }
        }
    }
}

/// Множители касаний между проходами одного подшага (`ContactPassCache`): списки по возрастанию ключа пары.
#[derive(Clone, Debug, Default)]
pub struct ContactPassCache {
    keys: Vec<i64>,
    lambda: Vec<f32>,
    initial: Vec<f32>,
    approach: Vec<f32>,
    count: usize,
    cursor: usize,
}

impl ContactPassCache {
    #[inline]
    fn key(a: i32, b: i32) -> i64 {
        ((a as i64) << 32) | b as i64
    }

    pub fn save(&mut self, n: usize, a: &[i32], b: &[i32], l: &[f32], init: &[f32], vn: &[f32]) {
        if self.keys.len() < n {
            self.keys = vec![0; n * 2];
            self.lambda = vec![0.0; n * 2];
            self.initial = vec![0.0; n * 2];
            self.approach = vec![0.0; n * 2];
        }
        for i in 0..n {
            self.keys[i] = Self::key(a[i], b[i]);
            self.lambda[i] = l[i];
            self.initial[i] = init[i];
            self.approach[i] = vn[i];
        }
        self.count = n;
        self.cursor = 0;
    }

    pub fn restore(&mut self, i: usize, a: i32, b: i32, l: &mut [f32], init: &mut [f32], vn: &mut [f32]) {
        let k = Self::key(a, b);
        while self.cursor < self.count && self.keys[self.cursor] < k {
            self.cursor += 1;
        }
        if self.cursor < self.count && self.keys[self.cursor] == k {
            l[i] = self.lambda[self.cursor];
            init[i] = self.initial[self.cursor];
            vn[i] = self.approach[self.cursor];
        }
    }
}

/// Наименьший положительный радиус касания (`ContactRadiusMinimum`): дерево минимумов.
#[derive(Clone, Debug)]
pub struct ContactRadiusMinimum {
    base: usize,
    tree: Vec<f32>,
}

/// `Integer.highestOneBit`.
#[inline]
fn highest_one_bit(x: i32) -> i32 {
    if x == 0 {
        0
    } else {
        (i32::MIN as u32 >> (x as u32).leading_zeros()) as i32
    }
}

impl ContactRadiusMinimum {
    pub fn new(n: usize) -> ContactRadiusMinimum {
        let base = (highest_one_bit(1i32.max(n as i32 - 1)) << 1) as usize;
        ContactRadiusMinimum { base, tree: vec![f32::INFINITY; base * 2] }
    }

    #[inline]
    pub fn value(&self) -> f32 {
        if self.tree[1].is_finite() {
            self.tree[1]
        } else {
            0.0
        }
    }

    pub fn reset(&mut self, radius: &[f32]) {
        self.tree.fill(f32::INFINITY);
        for (i, &r) in radius.iter().enumerate() {
            if r > 0.0 {
                self.tree[self.base + i] = r;
            }
        }
        for i in (1..self.base).rev() {
            self.tree[i] = minf(self.tree[2 * i], self.tree[2 * i + 1]);
        }
    }

    pub fn update(&mut self, v: usize, radius: f32) {
        let mut i = self.base + v;
        let value = if radius > 0.0 { radius } else { f32::INFINITY };
        if self.tree[i] == value {
            return;
        }
        self.tree[i] = value;
        while i > 1 {
            i >>= 1;
            let next = minf(self.tree[2 * i], self.tree[2 * i + 1]);
            if self.tree[i] == next {
                break;
            }
            self.tree[i] = next;
        }
    }
}

/// Заявки на гибель клетки без повторов (`ContactDamageCandidates`).
#[derive(Clone, Debug)]
pub struct ContactDamageCandidates {
    pub ids: Vec<i32>,
    marked: Vec<bool>,
    pub count: usize,
}

impl ContactDamageCandidates {
    pub fn new(capacity: usize) -> ContactDamageCandidates {
        ContactDamageCandidates { ids: vec![0; capacity], marked: vec![false; capacity], count: 0 }
    }

    #[inline]
    pub fn add(&mut self, v: i32) {
        if self.marked[v as usize] {
            return;
        }
        self.marked[v as usize] = true;
        self.ids[self.count] = v;
        self.count += 1;
    }

    pub fn clear(&mut self) {
        for k in 0..self.count {
            let v = self.ids[k] as usize;
            self.marked[v] = false;
        }
        self.count = 0;
    }
}

/// Толчки в жёсткие кости одного прохода (`RigidContactBatch`): сумма на кость, затем жёсткое движение кластера.
#[derive(Clone, Debug)]
pub struct RigidContactBatch {
    min_arm2: f32,
    x: Vec<f32>,
    y: Vec<f32>,
    torque: Vec<f32>,
    touched: Vec<bool>,
    list: Vec<i32>,
    list_n: usize,
}

impl RigidContactBatch {
    pub fn new(min_spin_arm: f32) -> RigidContactBatch {
        RigidContactBatch {
            min_arm2: min_spin_arm * min_spin_arm,
            x: Vec::new(),
            y: Vec::new(),
            torque: Vec::new(),
            touched: Vec::new(),
            list: vec![0; 16],
            list_n: 0,
        }
    }

    pub fn begin(&mut self, count: usize) {
        if self.x.len() != count {
            self.x = vec![0.0; count];
            self.y = vec![0.0; count];
            self.torque = vec![0.0; count];
            self.touched = vec![false; count];
            self.list_n = 0;
            return;
        }
        for k in 0..self.list_n {
            let b = self.list[k] as usize;
            self.x[b] = 0.0;
            self.y[b] = 0.0;
            self.torque[b] = 0.0;
            self.touched[b] = false;
        }
        self.list_n = 0;
    }

    #[inline]
    pub fn add(&mut self, b: usize, rx: f32, ry: f32, jx: f32, jy: f32) {
        if !self.touched[b] {
            self.touched[b] = true;
            if self.list_n == self.list.len() {
                let cap = self.list_n * 2;
                self.list.resize(cap, 0);
            }
            self.list[self.list_n] = b as i32;
            self.list_n += 1;
        }
        self.x[b] += jx;
        self.y[b] += jy;
        self.torque[b] += rx * jy - ry * jx;
    }

    /// Движение кластеров по позициям (`positions = true`): out — те же px/py, центры костей сдвигаются.
    #[allow(clippy::too_many_arguments)]
    pub fn apply_positions(
        &mut self,
        ids: &[Vec<i32>],
        mass: &[f32],
        inertia: &[f32],
        cx: &mut [f32],
        cy: &mut [f32],
        px: &mut [f32],
        py: &mut [f32],
        inv_mass: &[f32],
    ) {
        self.list[..self.list_n].sort_unstable();
        for k in 0..self.list_n {
            let b = self.list[k] as usize;
            if mass[b] <= 0.0 || (self.x[b] == 0.0 && self.y[b] == 0.0 && self.torque[b] == 0.0) {
                continue;
            }
            let tx = self.x[b] / mass[b];
            let ty = self.y[b] / mass[b];
            let angle = if inertia[b] > mass[b] * self.min_arm2 { self.torque[b] / inertia[b] } else { 0.0 };
            let sin = strict_sin(angle as f64) as f32;
            let cos_minus_one = strict_cos(angle as f64) as f32 - 1.0;
            for &i in &ids[b] {
                let i = i as usize;
                if inv_mass[i] <= 0.0 {
                    continue;
                }
                let rx = px[i] - cx[b];
                let ry = py[i] - cy[b];
                px[i] += tx + cos_minus_one * rx - sin * ry;
                py[i] += ty + sin * rx + cos_minus_one * ry;
            }
            cx[b] += tx;
            cy[b] += ty;
        }
    }

    /// Движение кластеров по скоростям (`positions = false`): угол — малый, без тригонометрии.
    #[allow(clippy::too_many_arguments)]
    pub fn apply_velocities(
        &mut self,
        ids: &[Vec<i32>],
        mass: &[f32],
        inertia: &[f32],
        cx: &[f32],
        cy: &[f32],
        px: &[f32],
        py: &[f32],
        inv_mass: &[f32],
        vx: &mut [f32],
        vy: &mut [f32],
    ) {
        self.list[..self.list_n].sort_unstable();
        for k in 0..self.list_n {
            let b = self.list[k] as usize;
            if mass[b] <= 0.0 || (self.x[b] == 0.0 && self.y[b] == 0.0 && self.torque[b] == 0.0) {
                continue;
            }
            let tx = self.x[b] / mass[b];
            let ty = self.y[b] / mass[b];
            let angle = if inertia[b] > mass[b] * self.min_arm2 { self.torque[b] / inertia[b] } else { 0.0 };
            let sin = angle;
            let cos_minus_one = 0.0f32;
            for &i in &ids[b] {
                let i = i as usize;
                if inv_mass[i] <= 0.0 {
                    continue;
                }
                let rx = px[i] - cx[b];
                let ry = py[i] - cy[b];
                vx[i] += tx + cos_minus_one * rx - sin * ry;
                vy[i] += ty + sin * rx + cos_minus_one * ry;
            }
        }
    }
}

/// Ключи пар подшага (`PairKeyBuffer`): новые (перекладываемая вершина — меньшая) и нижние.
#[derive(Clone, Debug)]
pub struct PairKeyBuffer {
    pub new_keys: Vec<i64>,
    pub new_n: usize,
    pub low_keys: Vec<i64>,
    pub low_n: usize,
}

impl Default for PairKeyBuffer {
    fn default() -> Self {
        PairKeyBuffer { new_keys: vec![0; 256], new_n: 0, low_keys: vec![0; 256], low_n: 0 }
    }
}

impl PairKeyBuffer {
    #[inline]
    pub fn add_new(&mut self, k: i64) {
        if self.new_n == self.new_keys.len() {
            let cap = self.new_n * 2;
            self.new_keys.resize(cap, 0);
        }
        self.new_keys[self.new_n] = k;
        self.new_n += 1;
    }

    #[inline]
    pub fn add_low(&mut self, k: i64) {
        if self.low_n == self.low_keys.len() {
            let cap = self.low_n * 2;
            self.low_keys.resize(cap, 0);
        }
        self.low_keys[self.low_n] = k;
        self.low_n += 1;
    }

    /// Дописать свои ключи в конец [to].
    pub fn append_to(&self, to: &mut PairKeyBuffer) {
        if to.new_n + self.new_n > to.new_keys.len() {
            let cap = (to.new_keys.len() * 2).max(to.new_n + self.new_n);
            to.new_keys.resize(cap, 0);
        }
        to.new_keys[to.new_n..to.new_n + self.new_n].copy_from_slice(&self.new_keys[..self.new_n]);
        to.new_n += self.new_n;
        if to.low_n + self.low_n > to.low_keys.len() {
            let cap = (to.low_keys.len() * 2).max(to.low_n + self.low_n);
            to.low_keys.resize(cap, 0);
        }
        to.low_keys[to.low_n..to.low_n + self.low_n].copy_from_slice(&self.low_keys[..self.low_n]);
        to.low_n += self.low_n;
    }
}

/// Найденные пробитые рёбра (`PierceBuffer`): пары клеток подряд.
#[derive(Clone, Debug)]
pub struct PierceBuffer {
    pub edges: Vec<i32>,
    pub n: usize,
}

impl Default for PierceBuffer {
    fn default() -> Self {
        PierceBuffer { edges: vec![0; 32], n: 0 }
    }
}

impl PierceBuffer {
    #[inline]
    pub fn add(&mut self, a: i32, b: i32) {
        if self.n == self.edges.len() {
            let cap = self.n * 2;
            self.edges.resize(cap, 0);
        }
        self.edges[self.n] = a;
        self.edges[self.n + 1] = b;
        self.n += 2;
    }
}

/// Итог решения куска касаний (`ContactSolveSink`): заявки на гибель, счётчики, обратные массы концов.
#[derive(Clone, Debug)]
pub struct ContactSolveSink {
    pub kills: Vec<i32>,
    pub kill_n: usize,
    pub attract: i32,
    pub impulse: f32,
    pub wi: f32,
    pub wj: f32,
}

impl Default for ContactSolveSink {
    fn default() -> Self {
        ContactSolveSink { kills: vec![0; 32], kill_n: 0, attract: 0, impulse: 0.0, wi: 0.0, wj: 0.0 }
    }
}

impl ContactSolveSink {
    #[inline]
    pub fn add_kill(&mut self, v: i32) {
        if self.kill_n == self.kills.len() {
            let cap = self.kill_n * 2;
            self.kills.resize(cap, 0);
        }
        self.kills[self.kill_n] = v;
        self.kill_n += 1;
    }

    pub fn reset(&mut self) {
        self.kill_n = 0;
        self.attract = 0;
        self.impulse = 0.0;
    }
}
