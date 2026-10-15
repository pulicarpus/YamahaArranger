import com.yourapp.yamahaarranger.arranger.DrumKitShadowResolver as R
import java.io.File

fun main(args:Array<String>) {
    val root=File(args[0]);val kits=R.load(File(root,"app/src/main/assets/r22/kits.tsv.gz").inputStream(),"bec1368f783f847e1b3d433dd46a085968aa1118a9d19b4161bb34c08db4bff1")
    val resolver=R.Resolver(kits);var checks=0
    fun check(v:Boolean,label:String){require(v){label};checks++}
    val font=mapOf("COL" to "COLOMBO","TYR" to "TYROS4","PRM" to "PRIME_DRUM")
    val times=mutableListOf<Long>();val canonical=mutableListOf<String>()
    for(line in File(root,"tests/fixtures/r22/audit_ranges.tsv").readLines()) {
        val f=line.split('\t');val ch=f[1].toInt();val pc=f[2].toInt();val kit=kits.single { it.font==font[f[3]] && it.bank==f[4].toInt() && it.pc==f[5].toInt() }
        val hits=mutableListOf<R.Hit>()
        for(part in f[8].split(',')){val p=part.split(':').map { it.toInt() };val k=p[0];val count=p[1];val lo=p[2];val hi=p[3]
            val coverageByVelocity=(lo..hi).map { v ->R.coverage(kit,listOf(R.Hit(k,v))).covered }.toSet()
            check(coverageByVelocity.size==1,"audit min-max coverage invariant ${f.take(6)} key=$k")
            // Range-invariant coverage proves weighted total, without inventing an event velocity histogram.
            hits+=R.Hit(k,lo,count)
        }
        val coverage=R.coverage(kit,hits)
        check(coverage.covered==f[6].toInt() && coverage.total==f[7].toInt(),"audit coverage ${f.take(6)} $coverage")
        if(f[0]=="MainA" && ch==9 && f[3]=="PRM")check(coverage.missingKeys==listOf(33),"Kendang missing33")
        if(f[0]=="MainB" && ch==8 && f[3]=="PRM")check(coverage.missingKeys==listOf(34),"Alex missing34")
        val request=R.Request("audit-range-not-source-style-sha","SFF2",f[0],ch,127,0,pc,"SINT_SETUP")
        val d=resolver.evaluate(request,hits);check(d.ideal?.kit?.font=="COLOMBO" && d.ideal.kit.bank==127 && d.ideal.kit.pc==pc,"ROSITA table ideal ${f.take(3)} $d")
        canonical+="${f.take(6)}:${d.ideal?.kit?.id}:${d.ideal?.coverage}:${d.alternatives.map { it.kit.id to it.reason }}"
        check((1..3).all { R.Resolver(kits).evaluate(request,hits)==d },"determinism across fresh resolvers")
        repeat(25){n->val start=System.nanoTime();resolver.evaluate(request.copy(styleHash="cold-$n-${f.take(6)}"),hits);times+=System.nanoTime()-start}
    }
    val hit=listOf(R.Hit(36,90));val req=R.Request("synthetic","SFF1","MainA",9,127,0,0,"SINT_SETUP")
    check(resolver.evaluate(req,hit).ideal?.kit?.namespace=="YAMAHA_XG","SFF1 XG")
    for(msb in listOf(120,128,0))check(resolver.evaluate(req.copy(msb=msb),hit).ideal?.kit?.namespace=="GS","GS/GM $msb")
    for(r in listOf(req.copy(msb=126),req.copy(msb=64),req.copy(msb=null,lsb=null),req.copy(lsb=1),req.copy(source="NAME_GUESS")))check(resolver.evaluate(r,hit).ideal==null,"unknown/custom absent identity unresolved $r")
    val custom=kits.first { it.font=="PRIME_DRUM" && it.pc==0 }
    check(resolver.evaluate(req.copy(customIdentity=custom.id),hit).ideal?.kit?.id==custom.id,"explicit custom fingerprint identity")
    check(resolver.evaluate(req.copy(customIdentity="wrong fingerprint"),hit).ideal==null,"unreviewed custom rejected")
    check(resolver.evaluate(req,listOf(R.Hit(0,1))).ideal==null,"missing key coverage gate")
    println("DECISIONS_SHA256 "+R.sha256(canonical.joinToString("\n").toByteArray()))
    val p99=times.sorted()[((times.size-1)*99)/100]/1000.0
    println("R22 AUDIT_RANGE_G5 PASS 24 candidates; determinism 3 fresh resolvers; universal cases; checks=$checks coldP99Us=$p99 originalROSITAByteReplay=UNVERIFIED")
    check(p99<2000.0,"G6 host p99 <2ms: $p99")
}
