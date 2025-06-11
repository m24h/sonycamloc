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

class BLE private constructor(val handler:Handler,
                              val autoConnect: Boolean,
                              val onService:(()->Unit)?,
                              val onCharacteristic:((BluetoothGattCharacteristic, ByteArray)->Unit)?,
                              val onConnect:((Boolean)->Unit)?)
: BluetoothGattCallback(), AutoCloseable {
    companion object {
        // open a GATT object
        @Suppress("unused")
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun open (context:Context, device:BluetoothDevice, autoConnect:Boolean=true, looper:Looper?=null,
                  onService:(()->Unit)? =null,
                  onCharacteristic:((BluetoothGattCharacteristic, ByteArray)->Unit)? =null,
                  onConnect:((Boolean)->Unit)?=null) : BLE? {
            val ble=BLE(Handler(looper?:context.mainLooper), autoConnect,
                        onService, onCharacteristic, onConnect)
            return device.connectGatt(context, autoConnect, ble,
                       BluetoothDevice.TRANSPORT_AUTO, BluetoothDevice.PHY_LE_1M_MASK,
                       ble.handler)
                ?.let { ble.apply {gatt=it} }
        }
    }

    lateinit var gatt:BluetoothGatt
    var isConnected = false
    var result : CancellableContinuation<Any?>? = null
    override fun onDescriptorRead(gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor, status: Int, value: ByteArray) {
        super.onDescriptorRead(gatt, descriptor, status, value)
        result?.resume(Pair(status, value))
        result=null
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor, status: Int) {
        super.onDescriptorWrite(gatt, descriptor, status)
        result?.resume(status)
        result=null
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt,
                                       characteristic: BluetoothGattCharacteristic, status: Int) {
        super.onCharacteristicWrite(gatt, characteristic, status)
        result?.resume(status)
        result=null
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        super.onCharacteristicChanged(gatt, characteristic, value)
        onCharacteristic?.invoke(characteristic, value)
    }

    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
        value: ByteArray, status: Int ) {
        super.onCharacteristicRead(gatt, characteristic, value, status)
        result?.resume(Pair(status, value))
        result=null
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        super.onMtuChanged(gatt, mtu, status)
        result?.resume(Pair(status, mtu))
        result=null
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        super.onServicesDiscovered(gatt, status)
        result?.resume(status)
        result=null
    }

    override fun onServiceChanged(gatt: BluetoothGatt) {
        super.onServiceChanged(gatt)
        onService?.invoke()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        super.onConnectionStateChange(gatt, status, newState)
        result=null
        isConnected = newState==BluetoothProfile.STATE_CONNECTED
        onConnect?.invoke(isConnected)
        if (autoConnect && !isConnected) connect()
    }

    /**
     * close the Gatt object, and this BLE object should not be used again, try to open a new one when needed
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun close() {
        result=null
        isConnected=false
        try { gatt.close() } catch (_:Exception) {}
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * disconnect from device, but do not close the Gatt object
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        result=null
        isConnected=false
        gatt.disconnect()
    }

    /**
     * try to connect to the device
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect() = gatt.connect()

    suspend inline fun wait(timeout: Long, crossinline action:()->Boolean?) : Any?
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

    /**
     * read from characteristic
     * return null when timeout or not connected or starting failure, or (BluetoothGatt.GATT_XXX, ByteArray)
     */
    @Suppress("UNCHECKED_CAST", "unused")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun read(characteristic: BluetoothGattCharacteristic, timeout:Long) : Pair<Int, ByteArray>?
        = wait (timeout) { gatt.takeIf{isConnected}?.readCharacteristic(characteristic) } as? Pair<Int, ByteArray>
    /**
     * write to characteristic
     * return null when timeout or not connected or starting failure, or BluetoothGatt.GATT_XXX
     */
    @Suppress("unused")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun write(characteristic: BluetoothGattCharacteristic, bytes: ByteArray, writeType:Int, timeout:Long) :Int?
        = wait (timeout) { gatt.takeIf{isConnected}?.writeCharacteristic(characteristic, bytes,writeType)==BluetoothStatusCodes.SUCCESS } as? Int

    /**
     * read from Descriptor
     * return null when timeout or not connected or starting failure, or (BluetoothGatt.GATT_XXX, ByteArray)
     */
    @Suppress("UNCHECKED_CAST", "unused")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun read(descriptor: BluetoothGattDescriptor, timeout:Long) : Pair<Int, ByteArray>?
            = wait (timeout) { gatt.takeIf{isConnected}?.readDescriptor(descriptor) } as? Pair<Int, ByteArray>
    /**
     * write to Descriptor, return null when timeout or not connected or starting failure, or BluetoothGatt.GATT_XXX
     */
    @Suppress("unused")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun write(descriptor: BluetoothGattDescriptor, bytes: ByteArray, timeout:Long) :Int?
            = wait (timeout) { gatt.takeIf{isConnected}?.writeDescriptor(descriptor, bytes)==BluetoothStatusCodes.SUCCESS } as? Int

    /**
     * request MTU size
     * return null when timeout or not connected or starting failure, or (BluetoothGatt.GATT_XXX, Int)
     */
    @Suppress("UNCHECKED_CAST", "unused")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun requestMtu(mtu:Int, timeout:Long) : Pair<Int, Int>?
        = wait (timeout) { gatt.takeIf{isConnected}?.requestMtu(mtu)} as? Pair<Int, Int>

    /**
     * do services discovery (copy data from underlying bluetooth stack)
     * return null when timeout or not connected or starting failure, or (BluetoothGatt.GATT_XXX
     */
    @Suppress("unused")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun discoveryServices(timeout:Long)
        = wait (timeout) { gatt.takeIf{isConnected}?.discoverServices() } as? Int
}
