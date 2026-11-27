//! Блоки решателя — `solver/ConstraintBlocks.kt` и `solver/DenseBlockIndex.kt`.
//!
//! Частицы режутся на блоки по ~1000 подряд в пределах тела; ограничение, чьи концы в
//! одном блоке, решается в нём, иначе — в наборе шва своего тела. Чётные и нечётные блоки
//! независимы, поэтому их можно раздать воркерам без замков.

/// Блок пары частиц: свой, если оба конца в одном блоке, иначе шов тела.
#[inline]
pub fn block_of2(particle_block: &[i32], seam_bucket: &[i32], a: i32, b: i32) -> i32 {
    let pa = particle_block[a as usize];
    if pa == particle_block[b as usize] { pa } else { seam_bucket[pa as usize] }
}

#[inline]
pub fn block_of3(particle_block: &[i32], seam_bucket: &[i32], a: i32, b: i32, c: i32) -> i32 {
    let block = block_of2(particle_block, seam_bucket, a, b);
    if block == particle_block[c as usize] { block } else { seam_bucket[particle_block[a as usize] as usize] }
}

/// Список по блокам на двусвязных списках (`BlockIndex`).
#[derive(Clone, Debug)]
pub struct BlockIndex {
    pub head: Vec<i32>,
    pub tail: Vec<i32>,
    pub next: Vec<i32>,
    pub prev: Vec<i32>,
    pub owner: Vec<i32>,
    dense_items: Vec<Vec<i32>>,
    dense_dirty: Vec<bool>,
    pub dense_count: Vec<i32>,
}

impl BlockIndex {
    pub fn new(capacity: usize, blocks: usize) -> BlockIndex {
        BlockIndex {
            head: vec![-1; blocks],
            tail: vec![-1; blocks],
            next: vec![-1; capacity],
            prev: vec![-1; capacity],
            owner: vec![-1; capacity],
            dense_items: vec![Vec::new(); blocks],
            dense_dirty: vec![true; blocks],
            dense_count: vec![0; blocks],
        }
    }

    /// Плотная копия блока: пересобирается, только если список менялся.
    pub fn dense(&mut self, block: usize) -> &[i32] {
        if self.dense_dirty[block] {
            let mut size = 0usize;
            let mut c = self.head[block];
            while c >= 0 {
                size += 1;
                c = self.next[c as usize];
            }
            if self.dense_items[block].len() < size {
                self.dense_items[block] = vec![0; size * 2];
            }
            let items = &mut self.dense_items[block];
            let mut k = 0;
            c = self.head[block];
            while c >= 0 {
                items[k] = c;
                k += 1;
                c = self.next[c as usize];
            }
            self.dense_count[block] = size as i32;
            self.dense_dirty[block] = false;
        }
        &self.dense_items[block][..self.dense_count[block] as usize]
    }

    /// Плотный список блока — на время обхода, во время которого события меняют только связный список (Kotlin читает
    /// тот же массив без копии). Вернуть — [put_dense].
    pub fn take_dense(&mut self, block: usize) -> Vec<i32> {
        std::mem::take(&mut self.dense_items[block])
    }

    pub fn put_dense(&mut self, block: usize, items: Vec<i32>) {
        if self.dense_items[block].is_empty() {
            self.dense_items[block] = items;
        }
    }

    pub fn clear(&mut self) {
        self.head.fill(-1);
        self.tail.fill(-1);
        self.owner.fill(-1);
        self.dense_dirty.fill(true);
    }

    pub fn add(&mut self, id: i32, block: i32) {
        let idu = id as usize;
        if idu >= self.owner.len() {
            self.grow(idu + 1);
        }
        if self.owner[idu] >= 0 {
            return;
        }
        let b = block as usize;
        self.owner[idu] = block;
        self.next[idu] = -1;
        self.prev[idu] = self.tail[b];
        self.dense_dirty[b] = true;
        if self.tail[b] < 0 {
            self.head[b] = id;
        } else {
            let t = self.tail[b] as usize;
            self.next[t] = id;
        }
        self.tail[b] = id;
    }

    pub fn remove(&mut self, id: i32) {
        let idu = id as usize;
        if idu >= self.owner.len() {
            return;
        }
        let block = self.owner[idu];
        if block < 0 {
            return;
        }
        let b = block as usize;
        self.dense_dirty[b] = true;
        let p = self.prev[idu];
        let q = self.next[idu];
        if p < 0 {
            self.head[b] = q;
        } else {
            self.next[p as usize] = q;
        }
        if q < 0 {
            self.tail[b] = p;
        } else {
            self.prev[q as usize] = p;
        }
        self.owner[idu] = -1;
    }

    pub fn move_item(&mut self, from: i32, to: i32) {
        if from == to {
            return;
        }
        self.remove(to);
        let (fu, tu) = (from as usize, to as usize);
        let block = self.owner[fu];
        if block < 0 {
            return;
        }
        let b = block as usize;
        self.dense_dirty[b] = true;
        self.owner[tu] = block;
        self.prev[tu] = self.prev[fu];
        self.next[tu] = self.next[fu];
        if self.prev[tu] < 0 {
            self.head[b] = to;
        } else {
            let p = self.prev[tu] as usize;
            self.next[p] = to;
        }
        if self.next[tu] < 0 {
            self.tail[b] = to;
        } else {
            let q = self.next[tu] as usize;
            self.prev[q] = to;
        }
        self.owner[fu] = -1;
    }

    fn grow(&mut self, need: usize) {
        let old = self.owner.len();
        let size = need.max(16.max(old * 2));
        self.owner.resize(size, -1);
        self.next.resize(size, 0);
        self.prev.resize(size, 0);
    }
}

/// Плотный список по блокам (`DenseBlockIndex`): связи и треугольники подряд по блоку.
#[derive(Clone, Debug)]
pub struct DenseBlockIndex {
    pub start: Vec<i32>,
    pub count: Vec<i32>,
    pub live_count: Vec<i32>,
    pub items: Vec<i32>,
    pub owner: Vec<i32>,
    position: Vec<i32>,
    dirty: Vec<bool>,
    dirty_blocks: Vec<i32>,
    dirty_count: usize,
}

impl DenseBlockIndex {
    pub fn new(capacity: usize, blocks: usize) -> DenseBlockIndex {
        DenseBlockIndex {
            start: vec![0; blocks],
            count: vec![0; blocks],
            live_count: vec![0; blocks],
            items: vec![0; capacity],
            owner: vec![-1; capacity],
            position: vec![-1; capacity],
            dirty: vec![false; blocks],
            dirty_blocks: vec![0; blocks],
            dirty_count: 0,
        }
    }

    pub fn clear(&mut self) {
        self.owner.fill(-1);
        self.position.fill(-1);
        self.count.fill(0);
        self.live_count.fill(0);
        self.dirty.fill(false);
        self.dirty_count = 0;
    }

    #[inline]
    pub fn add(&mut self, id: i32, block: i32) {
        self.owner[id as usize] = block;
    }

    pub fn finish_build(&mut self) {
        self.count.fill(0);
        for &b in &self.owner {
            if b >= 0 {
                self.count[b as usize] += 1;
            }
        }
        let mut offset = 0;
        for b in 0..self.count.len() {
            self.start[b] = offset;
            offset += self.count[b];
            self.count[b] = 0;
        }
        for slot in 0..self.owner.len() {
            let id = slot;
            let b = self.owner[id];
            if b < 0 {
                continue;
            }
            let b = b as usize;
            let dst = (self.start[b] + self.count[b]) as usize;
            self.count[b] += 1;
            self.items[dst] = id as i32;
            self.position[id] = dst as i32;
        }
        self.live_count.copy_from_slice(&self.count);
    }

    pub fn remove(&mut self, id: i32) {
        let idu = id as usize;
        let b = self.owner[idu];
        if b < 0 {
            return;
        }
        let bu = b as usize;
        let pos = self.position[idu] as usize;
        self.items[pos] = -1;
        self.position[idu] = -1;
        self.owner[idu] = -1;
        self.live_count[bu] -= 1;
        if !self.dirty[bu] {
            self.dirty[bu] = true;
            self.dirty_blocks[self.dirty_count] = b;
            self.dirty_count += 1;
        }
    }

    pub fn move_item(&mut self, from: i32, to: i32) {
        if from == to {
            return;
        }
        self.remove(to);
        let (fu, tu) = (from as usize, to as usize);
        let b = self.owner[fu];
        if b < 0 {
            return;
        }
        let slot = self.position[fu];
        self.items[slot as usize] = to;
        self.owner[tu] = b;
        self.position[tu] = slot;
        self.owner[fu] = -1;
        self.position[fu] = -1;
    }

    pub fn pack_dirty(&mut self) {
        for k in 0..self.dirty_count {
            let b = self.dirty_blocks[k] as usize;
            let begin = self.start[b];
            let end = begin + self.count[b];
            let mut write = begin;
            for read in begin..end {
                let id = self.items[read as usize];
                if id >= 0 {
                    self.items[write as usize] = id;
                    self.position[id as usize] = write;
                    write += 1;
                }
            }
            self.count[b] = write - self.start[b];
            self.dirty[b] = false;
        }
        self.dirty_count = 0;
    }
}

/// Раскладка блоков и списки ограничений по ним (`ConstraintBlocks`).
#[derive(Clone, Debug)]
pub struct ConstraintBlocks {
    pub particle_block: Vec<i32>,
    pub first_particle: Vec<i32>,
    pub block_count: usize,
    pub seam: usize,
    pub buckets: usize,
    pub seam_bucket: Vec<i32>,
    pub body_run_of: Vec<i32>,
    pub body_runs: usize,
    pub serial_block: Vec<bool>,
    pub serial_blocks: i32,
    pub links: DenseBlockIndex,
    pub areas: DenseBlockIndex,
    pub limits: BlockIndex,
    pub bones: BlockIndex,
    pub bends: BlockIndex,
    pub flaps: BlockIndex,
    pub edges: BlockIndex,
    pub triangle_slot: Vec<i32>,
    limit_queue: Vec<i32>,
    active_limit_block: i32,
    queue_size: usize,
    pub dispatch_order: [Vec<i32>; 2],
    pub awake: Vec<i32>,
    pub near_items: Vec<Vec<i32>>,
    pub near_count: Vec<i32>,
    // Общий проход пределов по наборам: края, открытые разрывом в проходе, — в его хвост (находка 42).
    limit_pass_on: bool,
    limit_pass_id: i32,
    limit_pass_mark: Vec<i32>,
    pub pass_added: Vec<i32>,
    pub pass_added_n: usize,
    /// ОБЛАСТЬ ТИКА (острова, `docs/physics/islands.md`): область набора — [SCOPE_WORLD] мир, k ≥ 0 остров k; и
    /// область, которую сейчас обходят стадии ([SCOPE_ALL] — все наборы, как без островов). Наборы чужой области
    /// стадии пропускают, как спящий блок. Без островов все наборы — мира, обход прежний.
    pub bucket_scope: Vec<i32>,
    pub scope: i32,
    /// Вид острова (`islandView`, solver/islands.rs): блоки и швы острова по возрастанию — обход «по всем наборам» у
    /// вида идёт по ним (тот же порядок, что отбор [active] по всем наборам, без прохода по чужим).
    pub island_view: bool,
    pub island_blocks: Vec<i32>,
    pub island_seams: Vec<i32>,
}

/// Номер области: мир (всё, кроме островов) и все наборы сразу (`TickScope.kt`).
pub const SCOPE_WORLD: i32 = -1;
pub const SCOPE_ALL: i32 = -2;

/// Свои части вида острова (`islandView`): очередь пределов блока, края прохода, блоки и швы острова. Живут в
/// комплекте воркера между тиками; на время тика — внутри побитовой копии раскладки мира.
#[derive(Default)]
pub struct LayoutOwn {
    limit_queue: Vec<i32>,
    pass_added: Vec<i32>,
    island_blocks: Vec<i32>,
    island_seams: Vec<i32>,
}

impl ConstraintBlocks {
    /// Вид острова поверх побитовой копии раскладки мира: свои части — из [own], остальное — общие массивы мира
    /// (остров пишет только свои наборы).
    ///
    /// # Safety
    /// `self` — побитовая копия раскладки мира: её Vec принадлежат миру. Прежние значения своих полей не
    /// освобождаются (ptr::write); копию нельзя освобождать — только вернуть свои части [remove_island_own].
    pub unsafe fn install_island_own(&mut self, own: &mut LayoutOwn) {
        unsafe {
            std::ptr::write(&mut self.limit_queue, std::mem::take(&mut own.limit_queue));
            std::ptr::write(&mut self.pass_added, std::mem::take(&mut own.pass_added));
            std::ptr::write(&mut self.island_blocks, std::mem::take(&mut own.island_blocks));
            std::ptr::write(&mut self.island_seams, std::mem::take(&mut own.island_seams));
        }
        if self.limit_queue.is_empty() {
            self.limit_queue = vec![0; 64];
        }
        if self.pass_added.is_empty() {
            self.pass_added = vec![0; 16];
        }
        self.island_view = true;
        self.reset_pass_state();
    }

    /// Свои части вида — обратно в [own]; после этого копию не трогать.
    ///
    /// # Safety
    /// Только после [install_island_own] на той же копии.
    pub unsafe fn remove_island_own(&mut self, own: &mut LayoutOwn) {
        unsafe {
            own.limit_queue = std::ptr::read(&self.limit_queue);
            own.pass_added = std::ptr::read(&self.pass_added);
            own.island_blocks = std::ptr::read(&self.island_blocks);
            own.island_seams = std::ptr::read(&self.island_seams);
        }
    }

    /// `setIsland`: вид обходит остров [k] — его блоки и швы (по возрастанию); проходы — с нуля.
    pub fn set_island(&mut self, k: i32, blocks: &[i32], seams: &[i32]) {
        self.scope = k;
        self.island_blocks.clear();
        self.island_blocks.extend_from_slice(blocks);
        self.island_seams.clear();
        self.island_seams.extend_from_slice(seams);
        self.reset_pass_state();
    }
}

/// Курсор обхода наборов текущей области (`forActive`, `forActiveBlocks`): обход по номеру курсора, а не замыканием —
/// стадия между шагами обхода берёт состояние целиком.
#[derive(Clone, Copy, Debug)]
pub struct BucketWalk {
    from: usize,
    to: usize,
    /// Только блоки этой чётности (`forActiveBlocks`); usize::MAX — наборы [from, to).
    parity: usize,
    k: usize,
    seams: bool,
}

impl ConstraintBlocks {
    /// `forActive`: наборы [from, to) по возрастанию, которые обходит текущая область.
    pub fn walk(&self, from: usize, to: usize) -> BucketWalk {
        BucketWalk { from, to, parity: usize::MAX, k: if self.island_view { 0 } else { from }, seams: false }
    }

    /// `forActiveBlocks`: блоки чётности [parity] по возрастанию, которые обходит текущая область.
    pub fn walk_blocks(&self, parity: usize) -> BucketWalk {
        BucketWalk { from: 0, to: self.block_count, parity, k: if self.island_view { 0 } else { parity }, seams: false }
    }

    /// Следующий набор обхода [w] или None.
    pub fn next_active(&self, w: &mut BucketWalk) -> Option<usize> {
        if !self.island_view {
            let step = if w.parity == usize::MAX { 1 } else { 2 };
            while w.k < w.to {
                let b = w.k;
                w.k += step;
                if self.active(b) {
                    return Some(b);
                }
            }
            return None;
        }
        // Вид острова: его блоки, потом (кроме обхода блоков) его швы — каждый список по возрастанию.
        if !w.seams {
            while w.k < self.island_blocks.len() {
                let b = self.island_blocks[w.k] as usize;
                w.k += 1;
                let fits = if w.parity == usize::MAX { b >= w.from && b < w.to } else { (b & 1) == w.parity };
                if fits && self.active(b) {
                    return Some(b);
                }
            }
            if w.parity != usize::MAX {
                return None;
            }
            w.seams = true;
            w.k = 0;
        }
        while w.k < self.island_seams.len() {
            let s = self.island_seams[w.k] as usize;
            w.k += 1;
            if s >= w.from && s < w.to && self.active(s) {
                return Some(s);
            }
        }
        None
    }
    pub fn new(
        n: usize,
        body_of: &[i32],
        block_size: i32,
        link_capacity: usize,
        triangle_capacity: usize,
        bone_count: usize,
    ) -> ConstraintBlocks {
        let mut particle_block = vec![0i32; n];
        let mut max_body = -1;
        for i in 0..n {
            if body_of[i] > max_body {
                max_body = body_of[i];
            }
        }
        let mut body_size = vec![0i32; (max_body + 1) as usize];
        for i in 0..n {
            if body_of[i] >= 0 {
                body_size[body_of[i] as usize] += 1;
            }
        }
        let mut block: i32 = -1;
        let mut size = block_size;
        let mut body: i32 = -1;
        let mut prev_single = false;
        for i in 0..n {
            let o = body_of[i];
            let single = o >= 0 && body_size[o as usize] == 1;
            if size == block_size || (o != body && !(single && prev_single)) {
                block += 1;
                size = 0;
            }
            body = o;
            prev_single = single;
            particle_block[i] = block;
            size += 1;
        }
        let block_count = (block + 1) as usize;
        let seam = block_count;
        let mut first_particle = vec![0i32; block_count + 1];
        for i in (0..n).rev() {
            first_particle[particle_block[i] as usize] = i as i32;
        }
        first_particle[block_count] = n as i32;
        // НАБОР ШВОВ — НА ТЕЛО, А НЕ НА ПРОГОН ПОДРЯД ИДУЩИХ БЛОКОВ (25.11.2026, находка 42): у генома из нескольких
        // несвязанных кусков частицы тела лежат вперемешку, и прогонов у него несколько — их швы шли бы в разные
        // наборы, а наборы швов идут по воркерам параллельно. Частицы без тела (-1) — в общем первом наборе.
        let mut seam_bucket = vec![0i32; block_count];
        let mut body_run_of = vec![0i32; block_count];
        let mut org_blocks = vec![0i32; (max_body + 2) as usize];
        for b in 0..block_count {
            org_blocks[(body_of[first_particle[b] as usize] + 1) as usize] += 1;
        }
        let mut org_seam = vec![-1i32; (max_body + 2) as usize];
        let mut org_run = vec![-1i32; (max_body + 2) as usize];
        let mut seams = 0usize;
        let mut runs = 0i32;
        for b in 0..block_count {
            let o = (body_of[first_particle[b] as usize] + 1) as usize;
            if org_run[o] < 0 {
                org_run[o] = runs;
                runs += 1;
                org_seam[o] = if o > 0 && org_blocks[o] > 1 {
                    let s = (seam + seams) as i32;
                    seams += 1;
                    s
                } else {
                    seam as i32
                };
            }
            seam_bucket[b] = org_seam[o];
            body_run_of[b] = org_run[o];
        }
        let buckets = seam + seams.max(1);
        ConstraintBlocks {
            particle_block,
            first_particle,
            block_count,
            seam,
            buckets,
            seam_bucket,
            body_run_of,
            body_runs: runs as usize,
            serial_block: vec![false; block_count],
            serial_blocks: 0,
            links: DenseBlockIndex::new(link_capacity, buckets),
            limits: BlockIndex::new(link_capacity, buckets),
            areas: DenseBlockIndex::new(triangle_capacity, buckets),
            bones: BlockIndex::new(bone_count, buckets),
            bends: BlockIndex::new(n.max(16), buckets),
            flaps: BlockIndex::new(n.max(16), buckets),
            edges: BlockIndex::new(n.max(16), buckets),
            triangle_slot: vec![-1; triangle_capacity],
            limit_queue: vec![0; link_capacity],
            active_limit_block: -1,
            queue_size: 0,
            dispatch_order: [Vec::new(), Vec::new()],
            awake: vec![0; block_count + 1],
            near_items: vec![Vec::new(); buckets],
            near_count: vec![0; buckets],
            limit_pass_on: false,
            limit_pass_id: 0,
            limit_pass_mark: Vec::new(),
            pass_added: vec![0; 16],
            pass_added_n: 0,
            bucket_scope: vec![SCOPE_WORLD; buckets],
            scope: SCOPE_WORLD,
            island_view: false,
            island_blocks: Vec::new(),
            island_seams: Vec::new(),
        }
    }

    /// `active`: обходит ли текущая область набор [b] — блок бодрствующий и своей области, шов — своей области.
    #[inline]
    pub fn active(&self, b: usize) -> bool {
        if b < self.seam && self.awake[b] == 0 {
            return false;
        }
        let sc = self.scope;
        sc == SCOPE_ALL || self.bucket_scope[b] == sc
    }

    /// Очередь блока и общий проход пределов — как перед первым проходом (`resetPassState`: вид острова и откат).
    pub fn reset_pass_state(&mut self) {
        self.active_limit_block = -1;
        self.queue_size = 0;
        self.limit_pass_on = false;
        self.pass_added_n = 0;
    }

    /// `boneBucket`: набор, в котором проецируется кость из частиц [ids] — её блок, а набор швов тела, если кость
    /// лежит в нескольких блоках или до её частицы на две связи запечённого графа достаёт другой блок.
    pub fn bone_bucket(&self, ids: &[i32], vert_start: &[i32], vert_other: &[i32]) -> i32 {
        let home = self.particle_block[ids[0] as usize];
        for &i in ids {
            if self.particle_block[i as usize] != home || self.near_other_block(i, home, vert_start, vert_other) {
                return self.seam_bucket[home as usize];
            }
        }
        home
    }

    fn near_other_block(&self, i: i32, block: i32, start: &[i32], other: &[i32]) -> bool {
        let i = i as usize;
        for k in start[i]..start[i + 1] {
            let u = other[k as usize] as usize;
            if self.particle_block[u] != block {
                return true;
            }
            for q in start[u]..start[u + 1] {
                if self.particle_block[other[q as usize] as usize] != block {
                    return true;
                }
            }
        }
        false
    }

    pub fn begin_limit_pass(&mut self) {
        self.limit_pass_on = true;
        self.limit_pass_id += 1;
        self.pass_added_n = 0;
    }

    pub fn end_limit_pass(&mut self) {
        self.limit_pass_on = false;
    }

    /// Край [c] открыт разрывом этого прохода: наборы его пропускают, его проверит хвост прохода.
    #[inline]
    pub fn added_in_pass(&self, c: i32) -> bool {
        (c as usize) < self.limit_pass_mark.len() && self.limit_pass_mark[c as usize] == self.limit_pass_id
    }

    fn note_pass_added(&mut self, c: i32) {
        let cu = c as usize;
        if cu >= self.limit_pass_mark.len() {
            let cap = (cu + 1).max(self.limit_pass_mark.len() * 2).max(64);
            self.limit_pass_mark.resize(cap, 0);
        }
        self.limit_pass_mark[cu] = self.limit_pass_id;
        if self.pass_added_n == self.pass_added.len() {
            let cap = self.pass_added_n * 2;
            self.pass_added.resize(cap, 0);
        }
        self.pass_added[self.pass_added_n] = c;
        self.pass_added_n += 1;
    }

    /// Очередь пределов блока; растёт по месту (`queuePush`).
    fn queue_push(&mut self, c: i32) {
        if self.queue_size == self.limit_queue.len() {
            let cap = 64.max(self.queue_size * 2);
            self.limit_queue.resize(cap, 0);
        }
        self.limit_queue[self.queue_size] = c;
        self.queue_size += 1;
    }

    pub fn build_dispatch_order(&mut self) {
        for parity in 0..2usize {
            let count = (self.block_count + 1 - parity) / 2;
            let mut keys: Vec<i64> = (0..count)
                .map(|k| {
                    let block = parity + 2 * k;
                    let weight = self.first_particle[block + 1] - self.first_particle[block]
                        + self.links.live_count[block]
                        + self.areas.live_count[block];
                    (((i32::MAX - weight) as i64) << 32) | block as i64
                })
                .collect();
            keys.sort_unstable();
            self.dispatch_order[parity] = keys.iter().map(|&k| k as i32).collect();
        }
    }

    pub fn has_seams(&self) -> bool {
        for s in self.seam..self.buckets {
            if self.links.live_count[s] > 0
                || self.areas.live_count[s] > 0
                || self.limits.head[s] >= 0
                || self.bones.head[s] >= 0
                || self.bends.head[s] >= 0
                || self.flaps.head[s] >= 0
            {
                return true;
            }
        }
        false
    }

    #[inline]
    pub fn block_of(&self, a: i32, b: i32) -> i32 {
        block_of2(&self.particle_block, &self.seam_bucket, a, b)
    }

    #[inline]
    pub fn block_of3(&self, a: i32, b: i32, c: i32) -> i32 {
        block_of3(&self.particle_block, &self.seam_bucket, a, b, c)
    }

    pub fn add_near(&mut self, bucket: usize, c: i32) {
        let n = self.near_count[bucket] as usize;
        if n == self.near_items[bucket].len() {
            let cap = 16.max(n * 2);
            self.near_items[bucket].resize(cap, 0);
        }
        self.near_items[bucket][n] = c;
        self.near_count[bucket] = (n + 1) as i32;
    }

    pub fn add_limit(&mut self, c: i32, a: i32, b: i32) {
        if self.limits.owner.len() > c as usize && self.limits.owner[c as usize] >= 0 {
            return;
        }
        let block = self.block_of(a, b);
        self.limits.add(c, block);
        if self.limit_pass_on {
            self.note_pass_added(c);
        }
        if self.active_limit_block == block {
            self.queue_push(c);
        } else {
            self.add_near(block as usize, c);
        }
    }

    pub fn begin_limits(&mut self, block: usize) {
        self.active_limit_block = block as i32;
        self.queue_size = 0;
        let mut c = self.limits.head[block];
        while c >= 0 {
            self.queue_push(c);
            c = self.limits.next[c as usize];
        }
    }

    #[inline]
    pub fn limit_count(&self) -> usize {
        self.queue_size
    }
    #[inline]
    pub fn limit_at(&self, slot: usize) -> i32 {
        self.limit_queue[slot]
    }
    #[inline]
    pub fn end_limits(&mut self) {
        self.active_limit_block = -1;
    }

    pub fn remove_triangle(&mut self, id: usize) {
        let slot = self.triangle_slot[id];
        if slot >= 0 {
            self.areas.remove(slot);
            self.triangle_slot[id] = -1;
        }
    }

    pub fn move_triangle(&mut self, from: i32, to: i32, id: usize) {
        self.areas.move_item(from, to);
        self.triangle_slot[id] = to;
    }

    pub fn move_constraint(&mut self, from: i32, to: i32) {
        self.links.move_item(from, to);
        self.limits.move_item(from, to);
    }
}
