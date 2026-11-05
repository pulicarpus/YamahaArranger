package android.media.midi
import android.os.Handler
import android.os.Bundle
open class MidiReceiver { open fun onSend(data:ByteArray,offset:Int,count:Int,timestamp:Long){} }
open class MidiInputPort { @Throws(java.io.IOException::class) open fun send(data:ByteArray,offset:Int,count:Int){};open fun close(){} }
open class MidiOutputPort { open fun connect(receiver:MidiReceiver){};open fun close(){} }
open class MidiDevice { open fun openOutputPort(port:Int):MidiOutputPort?=null;open fun openInputPort(port:Int):MidiInputPort?=null;open fun close(){} }
open class MidiDeviceInfo(open val id:Int=0, open val inputPortCount:Int=0, open val outputPortCount:Int=0, open val type:Int=1) { val properties=Bundle()
    companion object { const val PROPERTY_NAME="name";const val PROPERTY_MANUFACTURER="manufacturer";const val PROPERTY_PRODUCT="product";const val TYPE_USB=1;const val TYPE_VIRTUAL=2;const val TYPE_BLUETOOTH=3 } }
open class MidiManager { open val devices:Array<MidiDeviceInfo> = arrayOf();open fun openDevice(info:MidiDeviceInfo,callback:(MidiDevice?)->Unit,handler:Handler){}
    open class DeviceCallback { open fun onDeviceAdded(device:MidiDeviceInfo){};open fun onDeviceRemoved(device:MidiDeviceInfo){} }
    open fun registerDeviceCallback(callback:DeviceCallback,handler:Handler?){} }
