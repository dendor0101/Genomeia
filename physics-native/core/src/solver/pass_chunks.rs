//! Куски поштучных проходов — `solver/PassChunks.kt` (25.11.2026, находка 42): по организмам. Одна нарезка на всех —
//! параллельные проходы и однопоточные суммы импульса берут куски отсюда; размер куска (PASS_CHUNK) и рез по
//! организмам входят в биты (частичные суммы групп складываются в порядке кусков). Куски области острова — куски мира,
//! обрезанные по её диапазонам (`restrict`).

use crate::params::PASS_CHUNK;

#[derive(Clone, Debug)]
pub struct PassChunks {
    /// Кусок k — части [part_start[k], part_start[k + 1]) в part_from/part_to. Без островов у куска часть одна.
    pub count: usize,
    pub part_start: Vec<i32>,
    pub part_from: Vec<i32>,
    pub part_to: Vec<i32>,
    pub part_count: usize,
    built_structure: i32,
    built_sleep: i32,
    built_n: i64,
    built_scope: i32,
}

impl Default for PassChunks {
    fn default() -> Self {
        PassChunks {
            count: 0,
            part_start: vec![0; 65],
            part_from: vec![0; 64],
            part_to: vec![0; 64],
            part_count: 0,
            built_structure: -1,
            built_sleep: -1,
            built_n: -1,
            built_scope: -1,
        }
    }
}

impl PassChunks {
    /// Пустая заглушка без выделения памяти: на место кусков, взятых на время прохода.
    pub fn empty() -> PassChunks {
        PassChunks {
            count: 0,
            part_start: Vec::new(),
            part_from: Vec::new(),
            part_to: Vec::new(),
            part_count: 0,
            built_structure: -1,
            built_sleep: -1,
            built_n: -1,
            built_scope: -1,
        }
    }

    #[inline]
    pub fn valid(&self, structure: i32, sleep_version: i32, n: usize) -> bool {
        self.built_structure == structure && self.built_sleep == sleep_version && self.built_n == n as i64 && self.built_scope == -1
    }

    pub fn mark_built(&mut self, structure: i32, sleep_version: i32, n: usize) {
        self.built_structure = structure;
        self.built_sleep = sleep_version;
        self.built_n = n as i64;
        self.built_scope = -1;
    }

    /// Куски области годны: та же структура, сон, число частиц и номер области (`valid` с `scopeVersion`).
    #[inline]
    pub fn valid_scope(&self, structure: i32, sleep_version: i32, n: usize, scope_version: i32) -> bool {
        self.built_structure == structure && self.built_sleep == sleep_version && self.built_n == n as i64 && self.built_scope == scope_version
    }

    pub fn mark_built_scope(&mut self, structure: i32, sleep_version: i32, n: usize, scope_version: i32) {
        self.built_structure = structure;
        self.built_sleep = sleep_version;
        self.built_n = n as i64;
        self.built_scope = scope_version;
    }

    /// `restrict`: куски области — каждый кусок мира [canon] (одна часть), обрезанный по диапазонам области
    /// [span_start, span_end). Пустые куски выпадают, порядок кусков и частей — прежний: частичная сумма группы
    /// копится по всем частям куска, как копилась бы по куску мира целиком.
    pub fn restrict(&mut self, canon: &PassChunks, span_count: usize, span_start: &[i32], span_end: &[i32]) {
        self.count = 0;
        self.part_count = 0;
        self.part_start[0] = 0;
        let mut s = 0usize;
        for k in 0..canon.count {
            let p = canon.part_start[k] as usize;
            let a = canon.part_from[p];
            let b = canon.part_to[p];
            while s < span_count && span_end[s] <= a {
                s += 1;
            }
            let mut q = s;
            while q < span_count && span_start[q] < b {
                let from = a.max(span_start[q]);
                let to = b.min(span_end[q]);
                if from < to {
                    self.add_part(from, to);
                }
                q += 1;
            }
            if self.part_count as i32 > self.part_start[self.count] {
                self.close_chunk();
            }
        }
    }

    /// Куски диапазонов [span_start, span_end). [last] — последняя частица организма; частица без организма (номер
    /// меньше нуля) — сама себе организм.
    pub fn build(&mut self, span_count: usize, span_start: &[i32], span_end: &[i32], organism_of: &[i32], last: &[i32]) {
        self.count = 0;
        self.part_count = 0;
        self.part_start[0] = 0;
        for k in 0..span_count {
            let end = span_end[k];
            let mut p = span_start[k];
            let mut open: i32 = -1;
            while p < end {
                let o = organism_of[p as usize];
                let mut u = if o >= 0 && (o as usize) < last.len() { last[o as usize] + 1 } else { p + 1 };
                if u <= p {
                    u = p + 1;
                }
                if u > end {
                    u = end;
                }
                if u - p > PASS_CHUNK {
                    if open >= 0 {
                        self.add(open, p);
                        open = -1;
                    }
                    let mut q = p;
                    while q < u {
                        let t = u.min(q + PASS_CHUNK);
                        self.add(q, t);
                        q = t;
                    }
                } else {
                    if open >= 0 && u - open > PASS_CHUNK {
                        self.add(open, p);
                        open = -1;
                    }
                    if open < 0 {
                        open = p;
                    }
                }
                p = u;
            }
            if open >= 0 {
                self.add(open, end);
            }
        }
    }

    /// Без сна (тесты): весь мир одним диапазоном, концы организмов — проходом по частицам.
    pub fn build_whole(&mut self, n: usize, organism_of: &[i32], last_scratch: &mut [i32]) {
        last_scratch.fill(-1);
        for i in 0..n {
            let o = organism_of[i];
            if o >= 0 && (o as usize) < last_scratch.len() {
                last_scratch[o as usize] = i as i32;
            }
        }
        self.build(1, &[0], &[n as i32], organism_of, last_scratch);
    }

    /// Начало и конец куска [k] (у куска мира часть одна).
    #[inline]
    pub fn range(&self, k: usize) -> (usize, usize) {
        let p = self.part_start[k] as usize;
        (self.part_from[p] as usize, self.part_to[p] as usize)
    }

    fn add(&mut self, a: i32, b: i32) {
        self.add_part(a, b);
        self.close_chunk();
    }

    fn add_part(&mut self, a: i32, b: i32) {
        if self.part_count == self.part_from.len() {
            let cap = self.part_count * 2;
            self.part_from.resize(cap, 0);
            self.part_to.resize(cap, 0);
        }
        self.part_from[self.part_count] = a;
        self.part_to[self.part_count] = b;
        self.part_count += 1;
    }

    fn close_chunk(&mut self) {
        if self.count + 1 == self.part_start.len() {
            let cap = self.part_start.len() * 2;
            self.part_start.resize(cap, 0);
        }
        self.count += 1;
        self.part_start[self.count] = self.part_count as i32;
    }
}
