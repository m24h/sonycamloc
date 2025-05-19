package top.m24h.android

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

open class GattReadWrite (val retry : Int =3) : BluetoothGattCallback() {
    var continuation : Continuation<Unit>? =null
    var bytes : ByteArray? =null
    var tried=0
    var error=BluetoothGatt.GATT_SUCCESS
    var gatt: BluetoothGatt? =null

    open fun onStateChange() {
        continuation?.resume(Unit)
        continuation=null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicWrite(
        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
    ) {
        super.onCharacteristicWrite(gatt, characteristic, status)
        if (bytes==null || status==BluetoothGatt.GATT_SUCCESS) {
            bytes=null
            tried=0
            onStateChange()
        } else if (tried<retry){
            tried++
            gatt.writeCharacteristic(characteristic, bytes!!, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            error=status
            onStateChange()
        }
    }

    @Suppress("unused")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    open fun write(characteristic: BluetoothGattCharacteristic,
                   bytes: ByteArray, continuation : Continuation<Unit>? =null)
    {
        this.bytes=bytes
        tried=0
        if (gatt?.writeCharacteristic(characteristic, bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            ==BluetoothStatusCodes.SUCCESS) {
            error==BluetoothGatt.GATT_SUCCESS
            this.continuation=continuation
        } else {
            error=BluetoothGatt.GATT_FAILURE
            onStateChange()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicRead(
        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int
    ) {
        super.onCharacteristicRead(gatt, characteristic, value, status)
        if (status==BluetoothGatt.GATT_SUCCESS) {
            bytes=value
            tried=0
            onStateChange()
        } else if (tried<retry){
            tried++
            gatt.readCharacteristic(characteristic)
        } else {
            error=status
            onStateChange()
        }
    }

    @Suppress("unused")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    open fun read(characteristic: BluetoothGattCharacteristic,
                  continuation : Continuation<Unit>? =null)
    {
        this.bytes=null
        tried=0
        if (gatt?.readCharacteristic(characteristic)==true) {
            error=BluetoothGatt.GATT_SUCCESS
            this.continuation=continuation
        } else {
            error=BluetoothGatt.GATT_FAILURE
            onStateChange()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServiceChanged(gatt: BluetoothGatt) {
        super.onServiceChanged(gatt)
        gatt.discoverServices()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int ) {
        super.onServicesDiscovered(gatt, status)
        error=status
        if (status!=BluetoothGatt.GATT_SUCCESS) {
            this.gatt=null
            try {gatt.close()} catch(_:Exception) {}
        } else {
            this.gatt=gatt
        }
        onStateChange()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onConnectionStateChange(
        gatt: BluetoothGatt, status: Int, newState: Int
    ) {
        super.onConnectionStateChange(gatt, status, newState)
        error=status
        if (status!=BluetoothGatt.GATT_SUCCESS
            || newState!=BluetoothProfile.STATE_CONNECTED) {
            this.gatt=null
            try {gatt.close()} catch(_:Exception) {}
        } else {
            this.gatt=gatt
        }
        onStateChange()
    }

    @Suppress("unused")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    open fun connect(device: BluetoothDevice, context:Context, continuation : Continuation<Unit>? =null) {
        bytes=null
        this.continuation=continuation
        error=BluetoothGatt.GATT_SUCCESS
        tried=0
        try {gatt?.close()} catch(_:Exception) {}
        gatt=null
        device.connectGatt(context, false, this)
    }

    @Suppress("unused")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    open fun discoverServices(continuation : Continuation<Unit>? =null) {
        this.continuation=continuation
        error=BluetoothGatt.GATT_SUCCESS
        tried=0
        if (gatt?.discoverServices()!=true) {
            try {gatt?.close()} catch(_:Exception) {}
            error=BluetoothGatt.GATT_FAILURE
            gatt=null
            onStateChange()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    open fun close() {
        bytes=null
        continuation=null
        error=BluetoothGatt.GATT_SUCCESS
        tried=0
        try {gatt?.close()} catch(_:Exception) {}
        gatt=null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend inline fun suspendTimeoutClose(timeout:Long, crossinline action: GattReadWrite.(cont:Continuation<Unit>)->Unit) : Boolean {
        try {
            withTimeout(timeout) {
                suspendCancellableCoroutine<Unit> { cont -> action(cont) }
            }
        } catch (_: TimeoutCancellationException) {
            close()
            return false
        }
        return true
    }
}
