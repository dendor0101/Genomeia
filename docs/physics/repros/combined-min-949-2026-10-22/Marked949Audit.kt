package io.github.some_example_name.physics.lab.checks
import io.github.some_example_name.physics.*
import io.github.some_example_name.physics.lab.io.PlayerLog
import io.github.some_example_name.physics.lab.session.*
import java.io.File
fun main(args: Array<String>) {
 val log=PlayerLog.parse(File(args[0])); val h=log.header
 val d=DemoSession(h.getValue("body"),h.getValue("copies").toInt(),h.getValue("free").toInt(),h.getValue("killFraction").toDouble(),h.getValue("killSeed").toLong(),DemoSettings.forReplay(h))
 d.replayBoot(); val s=d.state
 var previous=BooleanArray(s.n)
 s.dbgSubstepHook={sub ->
  if(s.playTick in 2191..2193) {
   println("STEP ${s.playTick}.$sub org=${s.organismOf[949]} free=${s.isFree[949]}")
   for(i in 0 until s.n) if(s.cellDead[i]&&!previous[i]) println("DIED $i")
   for(c in 0 until s.conCount) if(s.conA[c]==949 || s.conB[c]==949) println("LINK ${s.conA[c]} ${s.conB[c]} dead=${s.conDead[c]} torn=${s.linkTorn[c]}")
   for(i in intArrayOf(949,947,950,814)) println("POS $i ${s.px[i]} ${s.py[i]} r=${s.contacts!!.contactRadiusOf(i)}")
   val ct=s.contacts!!
   for(k in 0 until ct.contactCount) if(ct.contactI(k)==949 || ct.contactJ(k)==949) println("CONTACT ${ct.contactI(k)} ${ct.contactJ(k)}")
   println(s.dbgTearLog)
  }
  s.cellDead.copyInto(previous)
  s.dbgTearLog=if(s.playTick in 2190..2193) StringBuilder() else null
 }
 d.replayRun(log.lines,{}, {})
 println("AUDIT DONE")
}
