package com.yourapp.audio

/** Separate JNI entry point; never routes through AudioEngineManager or the arranger player. */
object ManagedSf2AuditionNative {
    init { System.loadLibrary("yamaha_arranger_native") }
    external fun render(snapshot: String, bank: Int, rawPc: Int, sourceKey: Int, velocity: Int): Array<ByteArray>?
    fun decode(snapshot: java.io.File, request: ManagedSf2Audition.Request): ManagedSf2Audition.NativeResult {
        val result = render(snapshot.absolutePath, request.bank, request.rawPc, request.sourceKey, request.velocity)
            ?: error("Isolated BASS audition failed; ensure audio engine initialized and selected preset available")
        check(result.size == 2) { "Invalid native evidence" }
        return ManagedSf2Audition.NativeResult(result[0], result[1].toString(Charsets.UTF_8))
    }
}
