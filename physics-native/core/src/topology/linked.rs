//! `topology/LinkedPairs.kt` и `topology/BoundaryLinkLimits.kt`.

/// Связи целого тела — отсортированные ключи `min * 1000003 + max` для поиска «связаны ли».
#[derive(Clone, Debug, Default)]
pub struct LinkedPairs {
    keys: Vec<i64>,
}

impl LinkedPairs {
    #[inline]
    pub fn contains(&self, key: i64) -> bool {
        self.keys.binary_search(&key).is_ok()
    }

    /// Склейка без сжатия повторов, как в Kotlin (`plus`).
    pub fn plus(&self, other: &LinkedPairs) -> LinkedPairs {
        let mut merged = Vec::with_capacity(self.keys.len() + other.keys.len());
        merged.extend_from_slice(&self.keys);
        merged.extend_from_slice(&other.keys);
        merged.sort_unstable();
        LinkedPairs { keys: merged }
    }

    pub fn from(a: &[i32], b: &[i32], count: usize) -> LinkedPairs {
        let mut keys: Vec<i64> =
            (0..count).map(|c| (a[c].min(b[c]) as i64) * 1000003 + a[c].max(b[c]) as i64).collect();
        keys.sort_unstable();
        keys.dedup();
        LinkedPairs { keys }
    }
}

/// Связи контура для общего прохода предела длины: плотный список с дырами внутри прохода.
#[derive(Clone, Debug, Default)]
pub struct BoundaryLinkLimits {
    pub ids: Vec<i32>,
    position: Vec<i32>,
    pub count: usize,
    in_pass: bool,
    holes: bool,
    pub mod_count: i32,
}

impl BoundaryLinkLimits {
    pub fn reset(&mut self, capacity: usize) {
        self.mod_count += 1;
        if self.position.len() != capacity {
            self.ids = vec![0; capacity];
            self.position = vec![0; capacity];
        }
        self.position.fill(-1);
        self.count = 0;
        self.in_pass = false;
        self.holes = false;
    }

    pub fn add(&mut self, c: i32) {
        let cu = c as usize;
        if self.position[cu] >= 0 {
            return;
        }
        self.mod_count += 1;
        self.position[cu] = self.count as i32;
        self.ids[self.count] = c;
        self.count += 1;
    }

    pub fn remove(&mut self, c: i32) {
        let cu = c as usize;
        let slot = self.position[cu];
        if slot < 0 {
            return;
        }
        self.mod_count += 1;
        self.position[cu] = -1;
        if self.in_pass {
            self.ids[slot as usize] = -1;
            self.holes = true;
        } else {
            self.count -= 1;
            let last = self.ids[self.count];
            if (slot as usize) < self.count {
                self.ids[slot as usize] = last;
                self.position[last as usize] = slot;
            }
        }
    }

    pub fn grow(&mut self, capacity: usize) {
        if self.position.len() >= capacity {
            return;
        }
        self.ids.resize(capacity, 0);
        self.position.resize(capacity, -1);
    }

    pub fn begin_pass(&mut self) {
        self.in_pass = true;
    }

    pub fn end_pass(&mut self) {
        self.in_pass = false;
        if !self.holes {
            return;
        }
        let mut write = 0usize;
        for read in 0..self.count {
            let c = self.ids[read];
            if c < 0 {
                continue;
            }
            self.ids[write] = c;
            self.position[c as usize] = write as i32;
            write += 1;
        }
        self.count = write;
        self.holes = false;
    }
}
