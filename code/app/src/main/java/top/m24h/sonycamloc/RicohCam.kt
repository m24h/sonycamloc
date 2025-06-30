package top.m24h.sonycamloc

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.location.Location
import android.util.SparseArray
import androidx.annotation.RequiresPermission
import top.m24h.android.BLE
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

@Suppress("unused")
class RicohCam : Camera {
    companion object : CameraType {
        override val name = "Ricoh"
        override val needBind = true

        override fun match(manu: SparseArray<ByteArray>) =
            manu.get(1631)?.let{it.size>1}==true // it's tough, but I can't get more information

        override fun match(ble: BLE) =
            ble.gatt.getService(SERVICE_GEO)!=null

        override fun cameraClass(): Class<out Camera>
            =RicohCam::class.java

        val SERVICE_GEO_ENABLE = UUID.fromString("4B445988-CAA0-4DD3-941D-37B4F52ACA86")!!
        val CHAR_GEO_ENABLE = UUID.fromString("A36AFDCF-6B67-4046-9BE7-28FB67DBC071")!!
        val GEO_ENABLE = byteArrayOf(1)
        val GEO_DISABLE = byteArrayOf(0)
        val SERVICE_GEO = UUID.fromString("84A0DD62-E8AA-4D0F-91DB-819B6724C69E")!!
        val CHAR_GEO_DATA = UUID.fromString("28F59D60-8B8E-4FCD-A81F-61BDB46595A9")!!
        val SERVICE_REMOTE = UUID.fromString("9F00F387-8345-4BBC-8B92-B87B52E3091A")!!
        val CHAR_REMOTE_SHOT = UUID.fromString("559644B8-E0BC-4011-929B-5CF9199851E7")!!
        val REMOTE_FOCUS_DOWN = byteArrayOf(0, 1)
        val REMOTE_FOCUS_UP = byteArrayOf(0, 0)
        val REMOTE_SHOT_DOWN = byteArrayOf(1, 0)
        val REMOTE_SHOT_UP = byteArrayOf(2, 0)
        val CHAR_DRIVE_MODE = UUID.fromString("B29E6DE3-1AEC-48C1-9D05-02CEA57CE664")!!
        val DRIVE_SINGLE = byteArrayOf(0)

        fun makeGeoData(longitude: Double, latitude: Double, altitude: Double, timeInMillis: Long): ByteArray {
            val data = ByteArray(32)
            var t = latitude.toBits()
            for (i in 0..7) data[i] = (t ushr (56 - i * 8)).toByte()
            t = longitude.toBits()
            for (i in 0..7) data[i + 8] = (t ushr (56 - i * 8)).toByte()
            t = altitude.toBits()
            for (i in 0..7) data[i + 16] = (t ushr (56 - i * 8)).toByte()
            val calendarUTC = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendarUTC.timeInMillis = timeInMillis
            val year = calendarUTC.get(Calendar.YEAR)
            data[25] = (year ushr 8).toByte(); data[24] = year.toByte()
            data[26] = (calendarUTC.get(Calendar.MONTH) + 1).toByte()
            data[27] = calendarUTC.get(Calendar.DAY_OF_MONTH).toByte()
            data[28] = calendarUTC.get(Calendar.HOUR_OF_DAY).toByte()
            data[29] = calendarUTC.get(Calendar.MINUTE).toByte()
            data[30] = calendarUTC.get(Calendar.SECOND).toByte()
            data[31] = 0
            return data
        }
    }

    private var characteristicGeoData   : BluetoothGattCharacteristic? =null
    private var characteristicGeoEnable : BluetoothGattCharacteristic? =null
    private var characteristicRemote    : BluetoothGattCharacteristic? =null
    private var characteristicDrvMode   : BluetoothGattCharacteristic? =null
    
    override suspend fun discovery(ble: BLE) : Boolean {
        val srvRemote = ble.gatt.getService(SERVICE_REMOTE)
        characteristicRemote = srvRemote?.getCharacteristic(CHAR_REMOTE_SHOT)
        characteristicDrvMode = srvRemote?.getCharacteristic(CHAR_DRIVE_MODE)
        val srvGeo = ble.gatt.getService(SERVICE_GEO)
        characteristicGeoData = srvGeo?.getCharacteristic(CHAR_GEO_DATA)
        val srvGeoEnable = ble.gatt.getService(SERVICE_GEO_ENABLE)
        characteristicGeoEnable = srvGeoEnable?.getCharacteristic(CHAR_GEO_ENABLE)
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun config(ble: BLE): Boolean {
        return characteristicGeoData==null ||
               characteristicGeoEnable?.let{ble.write(it, GEO_ENABLE)==BluetoothGatt.GATT_SUCCESS}!=false
    }

    override val remoteFeatures
        get() = if (characteristicRemote!=null) "|Focus|Shot|" else ""

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun geoTag(ble: BLE, location: Location, timeInMillis: Long): Boolean {
        return characteristicGeoData?.let{
            ble.write(it,
                makeGeoData(location.longitude, location.latitude,
                    if (location.hasAltitude()) location.altitude else 0.0,
                    timeInMillis + 500) // make up for lost time
            )==BluetoothGatt.GATT_SUCCESS
        }!=false
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun remote(ble: BLE, feature: String, active: Boolean): Boolean {
        return characteristicRemote?.let { char ->
             (if (active) when (feature) {
                "Focus" -> REMOTE_FOCUS_DOWN
                "Shot" -> {
                    // my GR3 can only take photo in other than single-frame mode, and Ricoh "Image Sync" does not support either
                    // send "Shot" command in continuous mode cause unstoppable shooting
                    characteristicDrvMode?.let { ble.write(it, DRIVE_SINGLE) == BluetoothGatt.GATT_SUCCESS || return false }
                    REMOTE_SHOT_DOWN
                }
                else -> null
            } else when (feature) {
                "Focus" -> REMOTE_FOCUS_UP
                "Shot" -> REMOTE_SHOT_UP
                else -> null
            }) ?.let {
                ble.write(char, it)==BluetoothGatt.GATT_SUCCESS
            }
        } != false
    }
}