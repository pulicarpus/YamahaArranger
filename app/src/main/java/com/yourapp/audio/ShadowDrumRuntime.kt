package com.yourapp.audio

/** Fixed-capacity shadow command machine. No audio/native APIs, file I/O or logging.
 * Token identity is supplied by the adapter, not reconstructed from the most recent kit/key.
 * This is rehearsed offline; it is not connected to StyleSequencer. */
class ShadowDrumRuntime(capacity:Int=2048) {
    enum class Kind { ON, OFF, CHOKE }
    data class Route(val font:String,val handle:Long,val generation:String,val bank:Int,val pc:Int,
        val key:Int,val lane:Int,val exclusiveClass:Int=0,val legacy:Boolean=false)
    data class Command(val kind:Kind,val token:Long,val route:Route,val velocity:Int)
    private val tokens=LongArray(capacity)
    private val routes=arrayOfNulls<Route>(capacity)
    private val alive=BooleanArray(capacity)
    private val generations=arrayOfNulls<String>(capacity)
    var active=0;private set
    init { require(capacity in 1..65536) }
    /** Flag OFF preserves the legacy route object exactly, with no candidate evaluation. */
    fun on(token:Long,legacy:Route,candidate:Route?,velocity:Int,experimental:Boolean,emit:(Command)->Unit):Boolean {
        require(token>0 && velocity in 1..127)
        val old=(tokens.indices).firstOrNull { alive[it] && tokens[it]==token }
        if(old!=null)release(old,Kind.OFF,emit)
        val slot=(tokens.indices).firstOrNull { !alive[it] } ?: return false // no ON when no owner can be recorded
        val route=if(experimental && candidate!=null)candidate else legacy
        // Exclusive voices may be choked only within the exact verified voice domain.
        if(route.exclusiveClass>0)for(i in tokens.indices) {
            val peer=routes[i]
            if(alive[i] && peer!=null && !route.legacy && !peer.legacy &&
                peer.font==route.font && peer.handle==route.handle && peer.generation==route.generation &&
                peer.bank==route.bank && peer.pc==route.pc && peer.lane==route.lane && peer.exclusiveClass==route.exclusiveClass)
                release(i,Kind.CHOKE,emit)
        }
        tokens[slot]=token;routes[slot]=route;generations[slot]=route.generation;alive[slot]=true;active++
        emit(Command(Kind.ON,token,route,velocity));return true
    }
    fun off(token:Long,emit:(Command)->Unit):Boolean {
        val slot=(tokens.indices).firstOrNull { alive[it] && tokens[it]==token } ?: return false
        release(slot,Kind.OFF,emit);return true
    }
    private fun release(slot:Int,kind:Kind,emit:(Command)->Unit) {
        val route=routes[slot] ?: return
        alive[slot]=false;active--;routes[slot]=null;generations[slot]=null
        emit(Command(kind,tokens[slot],route,0))
    }
    /** Section/plan/flag changes do not reroute held owners. STOP/Ending cleanup targets each owner. */
    fun boundary(flush:Boolean,emit:(Command)->Unit) { if(flush)for(i in tokens.indices)if(alive[i])release(i,Kind.OFF,emit) }
    fun hasOwnersForGeneration(generation:String)=generations.indices.any { alive[it] && generations[it]==generation }
}

/** Activation is fail-closed. This milestone has no verified production lane/owner adapter. */
object DrumExperimentalActivation {
    const val PRODUCTION_ADAPTER_AVAILABLE=false
    data class Result(val requested:Boolean,val enabled:Boolean,val safeRows:Int,val blockedRows:Int,val reason:String)
    fun evaluate(requested:Boolean,plan:GenericDrumResolver.Plan)=Result(requested,
        requested && PRODUCTION_ADAPTER_AVAILABLE && plan.complete && plan.rows.any { it.selected!=null },
        plan.rows.count { it.selected!=null },plan.rows.count { it.selected==null },
        if(!requested)"FLAG_OFF_LEGACY_UNCHANGED" else if(!PRODUCTION_ADAPTER_AVAILABLE)"NO_VERIFIED_PRODUCTION_RESOURCE_LANE_OWNER_ADAPTER;shadow_complete;production_activation_blocked" else "PER_NOTE_SAFE_ONLY")
}
