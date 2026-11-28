package io.github.some_example_name.physics.lab.checks
import io.github.some_example_name.physics.*
import io.github.some_example_name.physics.lab.io.PlayerLog
import io.github.some_example_name.physics.lab.session.*
import java.io.File
fun main(args: Array<String>) {
 val log=PlayerLog.parse(File(args[0])); val h=log.header
 val hashes=LongArray(log.lines.last().tick)
 var verifiedTicks=0
 repeat(2) { pass ->
  val d=DemoSession(h.getValue("body"),h.getValue("copies").toInt(),h.getValue("free").toInt(),h.getValue("killFraction").toDouble(),h.getValue("killSeed").toLong(),DemoSettings.forReplay(h))
  d.state.quiet=true; d.replayBoot(); val s=d.state
  var ticks=0; var inside=0; var first=-1; var lastDead=false
  d.replayRun(log.lines,{}, {
   ticks++
   if(s.cellDead[949]&&!lastDead) println("DEATH pass=$pass tick=$ticks")
   lastDead=s.cellDead[949]
   if(!s.cellDead[949]) {
    val i=949; val x=s.px; val y=s.py
    for(t in 0 until s.triCount) {
     val a=s.triA[t]; val b=s.triB[t]; val c=s.triC[t]
     if(s.cellDead[a]||s.cellDead[b]||s.cellDead[c]||s.organismOf[i]==s.organismOf[a]) continue
     val d1=(x[b]-x[a])*(y[i]-y[a])-(y[b]-y[a])*(x[i]-x[a])
     val d2=(x[c]-x[b])*(y[i]-y[b])-(y[c]-y[b])*(x[i]-x[b])
     val d3=(x[a]-x[c])*(y[i]-y[c])-(y[a]-y[c])*(x[i]-x[c])
     if((d1>0&&d2>0&&d3>0)||(d1<0&&d2<0&&d3<0)) { inside++; if(first<0)first=ticks; break }
    }
   }
   val hash=d.replayStateHash()
   if(pass==0) hashes[ticks-1]=hash else check(hashes[ticks-1]==hash) {"Mismatch $ticks"}
   if(ticks==3217)println("MARK pass=$pass dead=${s.cellDead[949]} org=${s.organismOf[949]} insideTicks=$inside")
   if(ticks%3000==0)println("PROGRESS pass=$pass tick=$ticks")
  })
  println("CELL949 pass=$pass ticks=$ticks insideTicks=$inside first=$first oldOk=${d.replayHashOk} oldBad=${d.replayHashBad}")
  verifiedTicks=ticks
  check(inside==0) {"Cell949 enters tissue"}
 }
 println("Новый повтор: контрольные суммы: все ${verifiedTicks} сошлись")
}
