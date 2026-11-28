//! ОСТРОВА НА ВОРКЕРАХ — `solver/TickScope.kt`, `solver/IslandRun.kt`, `contacts/IslandBroadphase.kt`,
//! `solver/IslandChooser.kt` на Rust (`docs/physics/islands.md`).
//!
//! Остров — тело (группа тел), которое за тик не может коснуться никого снаружи. Его подшаги считает один воркер
//! целиком, острова идут параллельно, мир — после них, с воркерами по блокам. Острова битов не меняют: каждая область
//! начинает подшаги с той же фазы обхода связей и касаний, что и мир без островов, суммы по организмам копятся теми же
//! кусками (куски мира, обрезанные по области), пары касаний — канонические.
//!
//! ОБЛАСТЬ ТИКА. Стадии подшага обходят ТЕКУЩУЮ область: наборы блоков и швов (`ConstraintBlocks::scope`), диапазоны
//! частиц сна (`BodySleep::scope`, по организмам области), вершины касаний (`BoundaryContacts::scope`), организмы.
//! Номер области: [SCOPE_WORLD] — мир (всё, кроме островов), k ≥ 0 — остров k, [SCOPE_ALL] — всё сразу. Без островов
//! у всего область мира, и обход прежний.

//!
//! Файлы: здесь — область тика в стадиях; `island_plan.rs` — разбиение на острова, их списки и выбор режима по
//! замеру; `island_run.rs` — тень острова на воркере, прогон, снимок и откат.

use crate::solver::blocks::{SCOPE_ALL, SCOPE_WORLD};
use crate::solver::island_plan::{IslandChooser, IslandPlan, IslandScratch, ISLANDS_ON};
use crate::solver::island_run::IslandKit;
use crate::solver::pass_chunks::PassChunks;
use crate::state::BodyState;

/// Острова мира: области тика и всё, что нужно разбиению и прогону (живёт в `BodyState::isl`).
#[derive(Default)]
pub struct Islands {
    /// Область каждого организма и каждой частицы (вершины касаний) в этом тике (`islandOrgScope`,
    /// `islandVertexScope`): [SCOPE_WORLD] — мир, k ≥ 0 — остров k. Пустые, пока островов не было.
    pub org_scope: Vec<i32>,
    pub vertex_scope: Vec<i32>,
    /// Частиц в текущей области (`scopeParticles`): маленький мир после островов — без раздачи по воркерам; -1 — весь
    /// мир.
    pub scope_particles: i64,
    /// Куски поштучных проходов текущей области (`scopeChunkCache`): куски мира, обрезанные по её диапазонам.
    pub scope_chunks: PassChunks,
    /// Островов в этом тике (`islandCount`).
    pub count: usize,
    /// Это тень острова на воркере (`islandRun != null`): события топологии откатывают остров.
    pub shadow: bool,
    /// Тень встретила событие топологии (разрыв, гибель, пробой, пробуждение соседа) и пропустила его: остров
    /// откатится и пойдёт с миром (`IslandAbort` в Kotlin — здесь признак, без раскрутки стека: при раскрутке тень
    /// освободила бы общие с миром массивы, взятые на время стадии).
    pub abort: bool,
    /// Острова в этом тике (`islandsOn`): по режиму, в режиме по замеру — по выбору [chooser].
    pub on: bool,
    pub chooser: IslandChooser,
    pub scratch: IslandScratch,
    pub plan: IslandPlan,
    /// Комплекты воркеров островов (тень и её черновики), по номеру воркера (`islandKits`).
    pub kits: Vec<Option<Box<IslandKit>>>,
    /// Номер тика островов: тень воркера заводится заново, когда он сменился (`islandTick`).
    pub tick: i64,
    /// Островов в прошлом тике без откатившихся (`islandsLastTick`), частиц в них (`islandCellsLastTick`), откатов
    /// за всё время (`islandAborts`) — для стендов.
    pub last_tick: usize,
    pub cells_last_tick: usize,
    pub aborts: i64,
}

impl Islands {
    pub fn new(mode: i32) -> Islands {
        Islands { scope_particles: -1, on: mode == ISLANDS_ON, ..Default::default() }
    }
}

impl BodyState {
    /// Поштучные проходы идут по кускам области (острова в этом тике и область не «всё»).
    #[inline]
    pub fn chunks_scoped(&self) -> bool {
        self.sleep.org_scope_on && self.sleep.scope != SCOPE_ALL
    }

    /// `passChunks`: куски поштучных проходов текущей области — без островов куски мира по бодрствующим диапазонам, с
    /// островами куски мира (по всем бодрствующим), обрезанные по области. Читать — [active_chunks].
    pub fn ensure_pass_chunks(&mut self) {
        self.prepare_awake_spans();
        let (sv, n) = (self.structure_version, self.n);
        if !self.chunks_scoped() {
            let slv = self.sleep.version;
            if !self.pass_chunk_cache.valid(sv, slv, n) {
                let s = &self.sleep;
                self.pass_chunk_cache.build(s.span_count, &s.span_start, &s.span_end, &self.organism_of, &s.last);
                self.pass_chunk_cache.mark_built(sv, slv, n);
            }
            return;
        }
        self.sleep.rebuild_all_spans_if_needed(n, &self.organism_of);
        let slv = self.sleep.version;
        if !self.pass_chunk_cache.valid(sv, slv, n) {
            let s = &self.sleep;
            self.pass_chunk_cache.build(s.all_span_count, &s.all_span_start, &s.all_span_end, &self.organism_of, &s.last);
            self.pass_chunk_cache.mark_built(sv, slv, n);
        }
        let scv = self.sleep.scope_version;
        if !self.isl.scope_chunks.valid_scope(sv, slv, n, scv) {
            let s = &self.sleep;
            self.isl.scope_chunks.restrict(&self.pass_chunk_cache, s.span_count, &s.span_start, &s.span_end);
            self.isl.scope_chunks.mark_built_scope(sv, slv, n, scv);
        }
    }

    /// Куски текущей области (после [ensure_pass_chunks]).
    #[inline]
    pub fn active_chunks(&self) -> &PassChunks {
        if self.chunks_scoped() { &self.isl.scope_chunks } else { &self.pass_chunk_cache }
    }

    /// Куски текущей области — на время прохода, которому нужно и состояние целиком. Вернуть — [put_active_chunks].
    pub fn take_active_chunks(&mut self) -> PassChunks {
        let field = if self.chunks_scoped() { &mut self.isl.scope_chunks } else { &mut self.pass_chunk_cache };
        std::mem::replace(field, PassChunks::empty())
    }

    pub fn put_active_chunks(&mut self, chunks: PassChunks) {
        if self.chunks_scoped() {
            self.isl.scope_chunks = chunks;
        } else {
            self.pass_chunk_cache = chunks;
        }
    }

    /// `orgInScope`: входит ли организм [o] в текущую область (сон не смотрит). Без островов — всегда.
    #[inline]
    pub fn org_in_scope(&self, o: usize) -> bool {
        let s = &self.sleep;
        if !s.org_scope_on || s.scope == SCOPE_ALL {
            return true;
        }
        let os = if o < self.isl.org_scope.len() { self.isl.org_scope[o] } else { SCOPE_WORLD };
        os == s.scope
    }

    /// `forScopeOrganisms`, первый шаг: сколько позиций обойти — у тени острова его список организмов, иначе все
    /// организмы (с отбором [org_in_scope] через [scope_org_at]).
    #[inline]
    pub fn scope_org_slots(&self) -> usize {
        if self.sleep.island_view { self.sleep.island_orgs.len() } else { self.organism_count }
    }

    /// Организм позиции [x] обхода области или None (не её).
    #[inline]
    pub fn scope_org_at(&self, x: usize) -> Option<usize> {
        if self.sleep.island_view {
            return Some(self.sleep.island_orgs[x] as usize);
        }
        if self.org_in_scope(x) { Some(x) } else { None }
    }
}
