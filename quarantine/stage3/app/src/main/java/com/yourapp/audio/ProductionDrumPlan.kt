package com.yourapp.audio

/** Immutable worker-compiled addresses. RAW routing is an admission predicate:
 * the actual sequencer output must equal the original key/destination at dispatch.
 * Selecting a fingerprint is a resource policy, never a musical winner claim. */
class ProductionDrumPlan(val sections:Map<String,Table>,val safeRows:Int,val totalRows:Int,val digest:String) {
    class Table(val keys:LongArray,val routes:IntArray) {
        fun route(source:Int,destination:Int,bank:Int,pc:Int,key:Int,velocity:Int):Int {
            val k=address(source,destination,bank,pc,key,velocity)
            val at=java.util.Arrays.binarySearch(keys,k)
            return if(at>=0)routes[at] else 0
        }
    }
    companion object {
        fun address(source:Int,destination:Int,bank:Int,pc:Int,key:Int,velocity:Int):Long {
            if(source !in 0..15 || destination !in 8..9 || bank !in 0..16383 || pc !in 0..127 || key !in 0..127 || velocity !in 1..127)return -1
            return (((((source.toLong()*16+destination)*16384+bank)*128+pc)*128+key)*128+velocity)
        }
        /** Only static safety PASS rows can reach the native preflight. Exclusive
         * families stay blocked until a complete shared choke lane is implemented. */
        fun preparable(c:GenericDrumResolver.Candidate):Boolean {
            val required=listOf("SEMANTIC_AUTHORITY","NO_CONTRADICTORY_IDENTITY","RAW_ROUTING","FINGERPRINT_AND_LAYERS",
                "AUDITED_LAYER_MULTISET","VELOCITY_REGION","STATIC_PITCH","SAMPLE_PAIRING","STABLE_GENERATION","CHOKE_CLOSED_LANE")
            return required.all {c.gates[it]==DrumEngineeringProof.Status.PASS} &&
                c.evidence.target?.family!="HI_HAT" && c.layers.isNotEmpty() && c.layers.all { (it.ig[57]?:0)==0 && (it.pg[57]?:0)==0 &&
                    it.ig[47]==null && it.pg[47]==null }
        }
        fun from(plan:GenericDrumResolver.Plan,routeIds:Map<Pair<DrumShadowPlanner.Binding,Int>,Int>):ProductionDrumPlan {
            val sections=plan.rows.groupBy {it.request.section}.mapValues { (_,rows)->
                val items=rows.filter {it.selected!=null}.groupBy {r->with(r.request) {
                    address(sourceChannel,rhythmChannel,msb*128+lsb,rawPc,sourceKey,velocity)
                }}.mapNotNull { (key,rs)->
                    val ids=rs.mapNotNull {r->routeIds[r.selected!! to r.request.rhythmChannel]}.distinct()
                    if(key<0 || ids.size!=1 || rs.any {r->routeIds[r.selected!! to r.request.rhythmChannel]==null})null else key to ids.single()
                }.sortedBy {it.first}
                Table(items.map {it.first}.toLongArray(),items.map {it.second}.toIntArray())
            }
            return ProductionDrumPlan(sections,plan.rows.count {it.selected!=null},plan.rows.size,plan.digest)
        }
    }
}

/** Fixed-capacity FIFO includes legacy markers, so a legacy OFF cannot release a
 * subsequent substituted ON. Each logical source has its own queue. No objects
 * are allocated in reserve/commit/pop; duplicate source keys keep separate owners. */
class ProductionDrumOwners(private val capacity:Int=4096) {
    private val heads=IntArray(32*16*128){-1};private val tails=IntArray(32*16*128){-1}
    private val next=IntArray(capacity){it+1};private val tokens=LongArray(capacity)
    private var free=0
    var overflow=false;private set
    var size=0;private set
    init {require(capacity>0);next[capacity-1]=-1}
    fun reserve(source:Int,key:Int,part:Int=0):Int {
        if(source !in 0..15 || key !in 0..127 || part !in 0..31 || free<0) {overflow=true;return -1}
        val slot=free;free=next[slot];next[slot]=-1;tokens[slot]=0
        val id=(part*16+source)*128+key
        if(tails[id]<0)heads[id]=slot else next[tails[id]]=slot
        tails[id]=slot;size++;return slot
    }
    fun commit(slot:Int,token:Long) {require(slot in 0 until capacity);tokens[slot]=token}
    /** -1 = no owner, 0 = legacy marker, positive = captured native owner. */
    fun pop(source:Int,key:Int,part:Int=0):Long {
        if(source !in 0..15 || key !in 0..127 || part !in 0..31)return -1
        val id=(part*16+source)*128+key;val slot=heads[id];if(slot<0)return -1
        heads[id]=next[slot];if(heads[id]<0)tails[id]=-1
        val token=tokens[slot];tokens[slot]=0;next[slot]=free;free=slot;size--;return token
    }
    fun clear(release:(Long)->Unit) {
        if(size==0) {overflow=false;return}
        for(part in 0..31)for(source in 0..15)for(key in 0..127)while(heads[(part*16+source)*128+key]>=0) {
            val token=pop(source,key,part);if(token>0)release(token)
        }
        overflow=false
    }
}
