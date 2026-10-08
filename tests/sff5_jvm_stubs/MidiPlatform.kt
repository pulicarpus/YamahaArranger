package android.media.midi
import android.os.Handler
import android.os.Bundle
open class MidiReceiver { open fun onSend(data:ByteArray,offset:Int,count:Int,timestamp:Long){} }
open class MidiInputPort { @Throws(java.io.IOException::class) open fun send(data:ByteArray,offset:Int,count:Int){};open fun close(){} }
open class MidiOutputPort { open fun connect(receiver:MidiReceiver){};open fun close(){} }
open class MidiDevice { open fun openOutputPort(port:Int):MidiOutputPort?=null;open fun openInputPort(port:Int):MidiInputPort?=null;open fun close(){} }
open class MidiDeviceInfo { val outputPortCount=0;val properties=Bundle();companion object { const val PROPERTY_NAME="name" } }
open class MidiManager { val devices=arrayOf<MidiDeviceInfo>();open fun openDevice(info:MidiDeviceInfo,callback:(MidiDevice?)->Unit,handler:Handler){} }
