package top.m24h.android

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

@Suppress("unused")
class BLE private constructor(val handler:Handler,
                              val autoConnect: Boolean,
                              val onService:(BLE.()->Unit)?,
                              val onCharacteristic:(BLE.(BluetoothGattCharacteristic, ByteArray)->Unit)?,
                              val onConnect:(BLE.(Boolean)->Unit)?)
: BluetoothGattCallback(), AutoCloseable {
    companion object {
        /**
         * open a GATT object
          */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun open (context:Context,
                  device:BluetoothDevice,
                  autoConnect:Boolean=true,
                  looper:Looper?=null,
                  onService:(BLE.()->Unit)? =null,
                  onCharacteristic:(BLE.(BluetoothGattCharacteristic, ByteArray)->Unit)? =null,
                  onConnect:(BLE.(Boolean)->Unit)?=null) : BLE? {
            val ble=BLE(Handler(looper?:context.mainLooper), autoConnect,
                        onService, onCharacteristic, onConnect)
            return device.connectGatt(context, autoConnect, ble,
                       BluetoothDevice.TRANSPORT_AUTO, BluetoothDevice.PHY_LE_1M_MASK,
                       ble.handler)
                ?.let { ble.apply {gatt=it} }
        }
    }

    /**
     * underlying BluetoothGatt object
     */
    lateinit var gatt:BluetoothGatt

    /**
     * is it connected, should only read
     */
    private var _isConnected = false
    val isConnected get()=_isConnected

    /**
     * currently default timeout for bluetooth operation
     */
    var timeout : Long = 15000L

    override fun onDescriptorRead(gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor, status: Int, value: ByteArray) {
        super.onDescriptorRead(gatt, descriptor, status, value)
        resume(Pair(status, value))
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor, status: Int) {
        super.onDescriptorWrite(gatt, descriptor, status)
        resume(status)
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt,
                                       characteristic: BluetoothGattCharacteristic, status: Int) {
        super.onCharacteristicWrite(gatt, characteristic, status)
        resume(status)
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        super.onCharacteristicChanged(gatt, characteristic, value)
        onCharacteristic?.invoke(this, characteristic, value)
    }

    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
        value: ByteArray, status: Int ) {
        super.onCharacteristicRead(gatt, characteristic, value, status)
        resume(Pair(status, value))
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        super.onMtuChanged(gatt, mtu, status)
        resume(Pair(status, mtu))
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        super.onServicesDiscovered(gatt, status)
        resume(status)
    }

    override fun onServiceChanged(gatt: BluetoothGatt) {
        super.onServiceChanged(gatt)
        onService?.invoke(this)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        super.onConnectionStateChange(gatt, status, newState)
        _isConnected = newState==BluetoothProfile.STATE_CONNECTED
        onConnect?.invoke(this, _isConnected)
        resume(null)
    }

    /**
     * close the Gatt object, and this BLE object should not be used again, try to open a new one when needed
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun close() {
        _isConnected=false
        try { gatt.close() } catch (_:Exception) {}
        handler.removeCallbacksAndMessages(null)
        resume(null)
    }

    /**
     * disconnect from device, but do not close the Gatt object, so it can connect() again
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        _isConnected=false
        gatt.disconnect()
        resume(null)
    }

    /**
     * try to connect to the device
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect() = gatt.connect()

    private var result : CancellableContinuation<Any?>? = null
    private suspend inline fun wait(timeout: Long, crossinline action:()->Boolean?) : Any?
        = withTimeoutOrNull (timeout) {
            while (result!=null) delay(100)
            suspendCancellableCoroutine<Any?> {
                it.invokeOnCancellation { result=null }
                result=it
                if (action()!=true) {
                    it.resume(null)
                    result=null
                }
            }
        }
    private fun resume(res:Any?) {
        result?.resume(res)
        result=null
    }

    /**
     * read from characteristic
     * return null when timeout or not connected or starting failure, or (BluetoothGatt.GATT_XXX, ByteArray)
     */
    @Suppress("UNCHECKED_CAST")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun read(characteristic: BluetoothGattCharacteristic, timeout:Long=this.timeout) : Pair<Int, ByteArray>?
        = wait (timeout) { gatt.takeIf{isConnected}?.readCharacteristic(characteristic) } as? Pair<Int, ByteArray>
    /**
     * write to characteristic
     * return null when timeout or not connected or starting failure, or BluetoothGatt.GATT_XXX
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun write(characteristic: BluetoothGattCharacteristic, bytes: ByteArray, writeType:Int=BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, timeout:Long=this.timeout) :Int?
        = wait (timeout) { gatt.takeIf{isConnected}?.writeCharacteristic(characteristic, bytes,writeType)==BluetoothStatusCodes.SUCCESS } as? Int

    /**
     * read from Descriptor
     * return null when timeout or not connected or starting failure, or (BluetoothGatt.GATT_XXX, ByteArray)
     */
    @Suppress("UNCHECKED_CAST")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun read(descriptor: BluetoothGattDescriptor, timeout:Long=this.timeout) : Pair<Int, ByteArray>?
            = wait (timeout) { gatt.takeIf{isConnected}?.readDescriptor(descriptor) } as? Pair<Int, ByteArray>
    /**
     * write to Descriptor, return null when timeout or not connected or starting failure, or BluetoothGatt.GATT_XXX
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun write(descriptor: BluetoothGattDescriptor, bytes: ByteArray, timeout:Long=this.timeout) :Int?
            = wait (timeout) { gatt.takeIf{isConnected}?.writeDescriptor(descriptor, bytes)==BluetoothStatusCodes.SUCCESS } as? Int

    /**
     * request MTU size
     * return null when timeout or not connected or starting failure, or (BluetoothGatt.GATT_XXX, Int)
     */
    @Suppress("UNCHECKED_CAST")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun requestMtu(mtu:Int, timeout:Long=this.timeout) : Pair<Int, Int>?
        = wait (timeout) { gatt.takeIf{isConnected}?.requestMtu(mtu)} as? Pair<Int, Int>

    /**
     * do services discovery (copy data from underlying bluetooth stack)
     * return null when timeout or not connected or starting failure, or (BluetoothGatt.GATT_XXX
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun discoveryServices(timeout:Long=this.timeout)
        = wait (timeout) { gatt.takeIf{isConnected}?.discoverServices() } as? Int
}
