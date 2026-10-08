package android.content
open class Context {
    open fun getSystemService(name:String):Any?=null
    companion object { const val MIDI_SERVICE="midi" }
}
