package io.github.some_example_name.physics

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Отпечаток состояния мира Kotlin в формате physics-native/core/src/digest.rs: число разделов, затем разделы «имя,
 * длина, байты DataOutput». Порядок и состав разделов — ровно как там; поменял один — поменяй другой (сверка тут же
 * это покажет). Массивы float пишутся как в снимке мира: writeDoubles (float → double) или writeFloats.
 */
object RustDigest {
    fun of(st: BodyState): ByteArray {
        val all = ByteArrayOutputStream()
        val top = DataOutputStream(all)
        var sections = 0
        val body = ByteArrayOutputStream()
        val bodyOut = DataOutputStream(body)
        fun sec(name: String, f: DataOutputStream.() -> Unit) {
            val b = ByteArrayOutputStream()
            DataOutputStream(b).apply { f(); flush() }
            sections++
            bodyOut.writeUTF(name); bodyOut.writeInt(b.size()); bodyOut.write(b.toByteArray())
        }
        fun DataOutputStream.f(v: Float) = writeInt(java.lang.Float.floatToRawIntBits(v))
        val n = st.n
        val oc = st.organismCount
        with(st) {
            sec("counts") {
                writeInt(n); writeInt(oc); writeInt(lnkCount); writeInt(conCount); writeInt(triCount)
                writeInt(growEdgeN); writeInt(growTriN); writeInt(deadCount); writeInt(tornTotal); writeInt(structureVersion)
            }
            sec("px") { writeDoubles(px, n) }
            sec("py") { writeDoubles(py, n) }
            sec("prevX") { writeDoubles(prevX, n) }
            sec("prevY") { writeDoubles(prevY, n) }
            sec("vx") { writeDoubles(vx, n) }
            sec("vy") { writeDoubles(vy, n) }
            sec("invMass") { writeDoubles(invMass, n) }
            sec("restInvMass") { writeDoubles(restInvMass, n) }
            sec("matchWeight") { writeDoubles(matchWeight, n) }
            sec("radius") { writeDoubles(radius, n) }
            sec("cellDead") { writeBooleans(cellDead, n) }
            sec("cellUnborn") { writeBooleans(cellUnborn, n) }
            sec("inContact") { writeBooleans(inContact, n) }
            sec("crushedCell") { writeBooleans(crushedCell, crushedCell.size) }
            sec("contactPressure") { writeDoubles(contactPressure, contactPressure.size) }
            sec("contactPressureN") { writeInts(contactPressureN, contactPressureN.size) }
            sec("sleepAnchorX") { writeDoubles(sleepAnchorX, sleepAnchorX.size) }
            sec("sleepAnchorY") { writeDoubles(sleepAnchorY, sleepAnchorY.size) }
            sec("orgSleepDrift2") { writeDoubles(orgSleepDrift2, orgSleepDrift2.size) }
            sec("tickJitterX") { writeDoubles(tickJitterX, tickJitterX.size) }
            sec("tickJitterY") { writeDoubles(tickJitterY, tickJitterY.size) }
            sec("cellScale") {
                writeFloats(cellScale, minOf(n, cellScale.size)); writeFloats(cellScaleTarget, minOf(n, cellScaleTarget.size))
                writeFloats(cellScaleEff, cellScaleEff.size); writeInts(cellScaleSrc, cellScaleSrc.size)
                writeBoolean(cellScalesOn); writeBoolean(cellScalesMoving); writeBoolean(cellScalesDirty)
            }
            sec("cellMaterial") {
                writeDoubles(dragScale, minOf(n, dragScale.size)); writeFloats(cellSoftness, minOf(n, cellSoftness.size))
                writeBoolean(softnessOn)
                writeBooleans(cellNoContact, minOf(n, cellNoContact.size)); writeInts(cellGroup, minOf(n, cellGroup.size))
                writeFloats(cellGrip, minOf(n, cellGrip.size))
                writeDoubles(cellHomeCompliance, minOf(n, cellHomeCompliance.size))
                writeDoubles(cellHomeX, minOf(n, cellHomeX.size)); writeDoubles(cellHomeY, minOf(n, cellHomeY.size))
                writeBoolean(boundsOn)
                f(boundsMinX); f(boundsMinY); f(boundsMaxX); f(boundsMaxY)
            }
            sec("fibers") {
                writeFloats(cellFiberX, minOf(n, cellFiberX.size)); writeFloats(cellFiberY, minOf(n, cellFiberY.size))
                writeBoolean(fibersOn)
                writeFloats(conFiberC2, minOf(conCount, conFiberC2.size)); writeBooleans(triFiberById, triFiberById.size)
            }
            sec("boneOf") { writeInts(boneOf, n) }
            sec("boneClusterOf") { writeInts(boneClusterOf, minOf(n, boneClusterOf.size)) }
            sec("muscleOf") { writeInts(muscleOf, n) }
            sec("organismOf") { writeInts(organismOf, n) }
            sec("organisms") {
                writeInts(organismSize, oc); writeDoubles(organismMass, oc); writeDoubles(flowVX, oc); writeDoubles(flowVY, oc)
            }
            sec("bones") {
                writeInt(rigidBones.size)
                for (b in rigidBones.indices) {
                    writeInts(rigidBones[b], rigidBones[b].size)
                    writeDoubles(boneRestQx[b], boneRestQx[b].size); writeDoubles(boneRestQy[b], boneRestQy[b].size)
                }
                writeBoolean(bonesSplit); writeInt(boneDirtyN)
            }
            sec("muscles") {
                writeDoubles(muscleActivation, muscleActivation.size)
                writeDoubles(muscleActivationPrev, muscleActivationPrev.size)
                writeDoubles(muscleTarget, muscleTarget.size)
                writeDoubles(muscleScaleOf, muscleScaleOf.size)
            }
            sec("links") { writeInts(lnkA, lnkCount); writeInts(lnkB, lnkCount); writeInts(lnkEdge, lnkCount) }
            sec("constraints") {
                val cc = conCount
                writeInts(conA, cc); writeInts(conB, cc); writeDoubles(conRest, cc); writeInts(conMuscle, cc)
                writeInts(conEdge, cc); writeBooleans(conDead, cc); writeBooleans(linkTorn, cc)
                writeBooleans(conBoundary, cc); writeBooleans(conTendon, cc)
                writeFloats(conCellScale, cc); writeFloats(conSoftness, cc)
                writeBytes(conHold, minOf(conHold.size, cc)); writeDoubles(conGrowTarget, minOf(conGrowTarget.size, cc))
                writeBytes(conGrowAge, minOf(conGrowAge.size, cc)); writeInts(conOfEdge, conOfEdge.size)
            }
            sec("triangles") {
                val tc = triCount
                writeInts(triA, tc); writeInts(triB, tc); writeInts(triC, tc); writeFloats(triRestArea2, tc)
                writeInts(triId, tc); writeInts(triMuscle, tc); writeBooleans(triInverted, tc)
                writeFloats(triCellScale, tc); writeFloats(triSoftness, tc)
                writeBytes(triHold, minOf(triHold.size, tc)); writeFloats(triGrowTarget, triGrowTarget.size)
                writeInts(growEdges, growEdgeN); writeInts(growTris, growTriN)
            }
            sec("baked") {
                baked.writeState(this)
                writeInt(baked.bareEdges); writeInt(baked.liveTriangleCount); writeInt(baked.dirtyCount)
            }
            sec("sleep") { sleep.write(this, oc); writeInt(sleep.asleepCount) }
            sec("misc") {
                writeInt(dragId); f(mouseX); f(mouseY); writeBoolean(sweepBackwards)
                writeInt(playTick); writeBoolean(tearsPending)
                writeInts(tearQueue, tearQueueN); writeInt(tearQueueQuiet)
                writeInts(edgeDeathLog, 2 * edgeDeathN); writeInts(cellDeathLog, cellDeathN)
                writeInt(linkCapHits); writeInt(boneCapHits); writeInt(speedCapHits); f(peakSpeed2)
                writeInt(pressureBurstCount); writeInt(crushBurstCount); writeInt(piercedCount)
                writeInt(contactsVersion); writeBoolean(contactsStale)
            }
            sec("boundary") {
                val lb = localBoundary
                writeInts(lb.a, lb.count); writeInts(lb.b, lb.count); writeInts(lb.opposite, lb.count)
                writeBytes(lb.orientation, lb.count)
                writeInts(lb.bend.a, lb.bend.count); writeInts(lb.bend.b, lb.bend.count); writeDoubles(lb.bend.rest, lb.bend.count)
                writeInts(lb.flap.a, lb.flap.count); writeInts(lb.flap.b, lb.flap.count); writeDoubles(lb.flap.rest, lb.flap.count)
                writeInts(lb.flap.owner, lb.flap.count)
            }
            sec("surface") {
                val ls = localSurface
                writeDoubles(ls.radius, n); writeBooleans(ls.active, n); writeInts(ls.adjEnd, n)
                writeInts(ls.boundaryEnd, n); writeInts(ls.activeChanged, ls.activeChangedN)
                writeBooleans(isFree, n); writeBooleans(probe, n); writeDoubles(probeAngle, n)
            }
            sec("limits") { writeInts(boundaryLinkLimits.ids, boundaryLinkLimits.count) }
            sec("joints") {
                val s = jointSlots
                writeInt(s); writeInt(jointLiveCount)
                writeInts(jointA, s); writeInts(jointB, s); writeDoubles(jointRest, s); writeDoubles(jointCompliance, s)
                writeBooleans(jointLive, s); writeInts(jointFree, jointFreeN); writeDoubles(jointWakeStrain, s)
            }
            sec("growth") { writeFloats(contactRamp, contactRamp.size); writeInts(rampCells, rampN); writeInt(growStampNow) }
            sec("temp") {
                writeInts(tempRetireStart, tempRetireStart.size); writeInts(tempRetireEdge, tempRetireEdge.size)
                writeInts(tempRetireOther, tempRetireOther.size); writeBooleans(tempEdge, tempEdge.size)
                writeInts(tempPending, tempPendingN)
            }
            val cb = constraintBlocks
            if (cb != null) sec("blocks") {
                writeInt(cb.blockCount); writeInt(cb.buckets)
                writeInts(cb.awake, cb.awake.size)
                writeInts(cb.links.count, cb.links.count.size); writeInts(cb.links.liveCount, cb.links.liveCount.size)
                writeInts(cb.areas.count, cb.areas.count.size); writeInts(cb.areas.liveCount, cb.areas.liveCount.size)
                writeInts(cb.nearCount, cb.nearCount.size); writeInts(cb.triangleSlot, cb.triangleSlot.size)
            }
            val ct = contacts
            if (ct != null) {
                sec("contacts") { contactsDigest(ct, this) }
                sec("contactList") { contactListDigest(ct, this) }
                sec("broadphase") { broadphaseDigest(ct, this) }
            }
        }
        bodyOut.flush()
        top.writeInt(sections)
        top.write(body.toByteArray())
        top.flush()
        return all.toByteArray()
    }

    /** Поле касаний по имени: большинство их приватны, отпечатку нужны все. */
    private fun f(o: Any, name: String): Any? =
        o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)

    /**
     * Раздел «broadphase» — кэш широкой фазы (broadphase_digest в digest.rs): действительность сеток, пары-кандидаты,
     * число подвижных вершин. Острова Kotlin сбрасывают его на смене области (TickScope.setTickScope → setScope:
     * полная пересборка вместо дописывания), касания от этого те же — поэтому раздел отдельный и при возможных
     * островах не сверяется ([ignoredFor]).
     */
    private fun broadphaseDigest(ct: BoundaryContacts, o: DataOutputStream) = with(o) {
        writeBoolean(f(ct, "bpValid") as Boolean); writeBoolean(f(ct, "staticValid") as Boolean)
        val pairN = f(ct, "pairN") as Int
        writeInts(f(ct, "pairA") as IntArray, pairN); writeInts(f(ct, "pairB") as IntArray, pairN)
        writeInt(f(ct, "moverCount") as Int)
    }

    /**
     * Разделы, которые сверка мира [st] пропускает, если в Kotlin могут работать острова (в Rust их нет): кэш широкой
     * фазы и список касаний последнего подшага — острова держат свои касания в копиях, и в объекте мира к концу тика
     * остаются только касания области мира. Состояние частиц, топологии и пар покоя сверяется всегда; с выключенными
     * островами (`ISLANDS=0`) — всё.
     */
    fun ignoredFor(st: BodyState): Set<String> =
        if (st.islandsMode == ISLANDS_OFF) emptySet() else setOf("broadphase", "contactList")

    /**
     * Раздел «contactList» — касания последнего подшага (contact_list_digest в digest.rs): список, множители, гибель и
     * пробой. Живёт подшаг (снимок мира его не пишет).
     */
    private fun contactListDigest(ct: BoundaryContacts, o: DataOutputStream) = with(o) {
        val cN = ct.cN
        writeInt(cN); writeInts(ct.cI, cN); writeInts(ct.cJ, cN)
        writeDoubles(ct.cLamTot, cN); writeDoubles(f(ct, "cLamInit") as FloatArray, cN)
        writeDoubles(f(ct, "cVn") as FloatArray, cN)
        val damage = f(ct, "damage") as ContactDamageCandidates
        writeInts(damage.ids, damage.count)
        writeInts(ct.pierceA, ct.pierceN); writeInts(ct.pierceB, ct.pierceN)
    }

    /** Раздел «contacts» — тот же порядок, что contacts_digest в digest.rs. */
    private fun contactsDigest(ct: BoundaryContacts, o: DataOutputStream) = with(o) {
        fun fl(v: Float) = writeInt(java.lang.Float.floatToRawIntBits(v))
        val vertCount = f(ct, "vertCount") as Int
        writeInt(vertCount); writeInts(f(ct, "verts") as IntArray, vertCount)
        val onBound = f(ct, "onBound") as BooleanArray
        writeBooleans(onBound, onBound.size)
        val cr = f(ct, "contactRadius") as FloatArray
        writeDoubles(cr, cr.size)
        fl(f(ct, "cellSize") as Float); fl(f(ct, "bpMargin") as Float)
        val rs = f(ct, "restState") as ByteArray
        writeBytes(rs, rs.size)
        writeInt(f(ct, "restTouchN") as Int); writeInt(f(ct, "restSpacingN") as Int)
        writeBoolean(ct.ctBackwards)
        fl(ct.friction); writeInt(ct.immuneNow); writeBoolean(ct.contactRamp != null)
    }

    /** Разделы отпечатка: имя → байты, в порядке записи. */
    fun sections(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val input = DataInputStream(bytes.inputStream())
        val count = input.readInt()
        val out = LinkedHashMap<String, ByteArray>()
        repeat(count) {
            val name = input.readUTF()
            val data = ByteArray(input.readInt())
            input.readFully(data)
            out[name] = data
        }
        return out
    }

    /** null — отпечатки равны (кроме разделов [ignore]); иначе — первое расхождение словами. */
    fun firstDifference(kotlin: ByteArray, rust: ByteArray, ignore: Set<String> = emptySet()): String? {
        if (kotlin.contentEquals(rust)) return null
        val k = sections(kotlin); val r = sections(rust)
        if (k.keys.toList() != r.keys.toList()) return "разные списки разделов: Kotlin ${k.keys} / Rust ${r.keys}"
        for ((name, kb) in k) {
            if (name in ignore) continue
            val rb = r.getValue(name)
            if (kb.contentEquals(rb)) continue
            val at = (0 until minOf(kb.size, rb.size)).firstOrNull { kb[it] != rb[it] } ?: minOf(kb.size, rb.size)
            return "раздел «$name»: длина Kotlin ${kb.size}, Rust ${rb.size}; первый разный байт $at " +
                "(слово ${at / 4}, двойное ${at / 8}); Kotlin ${hex(kb, at)} / Rust ${hex(rb, at)}"
        }
        return if (ignore.isEmpty()) "отпечатки разные, но разделы совпали (сбой разбора)" else null
    }

    private fun hex(b: ByteArray, at: Int): String {
        val from = maxOf(0, at - 4); val to = minOf(b.size, at + 12)
        return (from until to).joinToString(" ") { (if (it == at) "[" else "") + "%02x".format(b[it]) + (if (it == at) "]" else "") }
    }
}
