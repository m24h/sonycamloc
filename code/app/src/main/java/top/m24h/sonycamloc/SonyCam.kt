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
import kotlin.math.round

@Suppress("unused")
class SonyCam : Camera {
    companion object : CameraType {
        override val name = "Sony"
        override val needBind = true

        override fun match(manu: SparseArray<ByteArray>) =
             (manu.get(301)?.let { it.size>1 && it[0].toInt() == 3 && it[1].toInt() == 0 }) == true

        override fun match(ble: BLE) =
            ble.gatt.getService(SERVICE_GEO)!=null

        override fun cameraClass(): Class<out Camera>
                =SonyCam::class.java

        val SERVICE_GEO=UUID.fromString("8000DD00-DD00-FFFF-FFFF-FFFFFFFFFFFF")!!
        val CHAR_GEO_CONF=UUID.fromString("0000DD21-0000-1000-8000-00805F9B34FB")!!
        val CHAR_GEO_DATA=UUID.fromString("0000DD11-0000-1000-8000-00805F9B34FB")!!
        val CHAR_GEO_SET30=UUID.fromString("0000DD30-0000-1000-8000-00805F9B34FB")!!
        val CHAR_GEO_SET31=UUID.fromString("0000DD31-0000-1000-8000-00805F9B34FB")!!
        val CHAR_GEO_SET_TIME=UUID.fromString("0000DD32-0000-1000-8000-00805F9B34FB")!!
        val CHAR_GEO_SET_ZONE=UUID.fromString("0000DD33-0000-1000-8000-00805F9B34FB")!!
        val SERVICE_REMOTE=UUID.fromString("8000FF00-FF00-FFFF-FFFF-FFFFFFFFFFFF")!!
        val CHAR_REMOTE_WRITE=UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")!!
        val GEO_ENABLE=byteArrayOf(1)
        val GEO_DISABLE=byteArrayOf(0)
        val REMOTE_FOCUS_DOWN=byteArrayOf(1,7)
        val REMOTE_FOCUS_UP=byteArrayOf(1,6)
        val REMOTE_SHOT_DOWN=byteArrayOf(1,9)
        val REMOTE_SHOT_UP=byteArrayOf(1,8)
        val REMOTE_TELE_DOWN=byteArrayOf(2,0x45,0x10)
        val REMOTE_TELE_UP=byteArrayOf(2,0x44,0)
        val REMOTE_WIDE_DOWN=byteArrayOf(2,0x47,0x10)
        val REMOTE_WIDE_UP=byteArrayOf(2,0x46,0)

        fun makeGeoData(longitude:Double, latitude:Double, timeInMillis:Long, diffMinuteUTC:Int? =null, diffMinuteDST:Int? =null):ByteArray {
            var data: ByteArray
            if (diffMinuteUTC==null || diffMinuteDST==null) {
                data=ByteArray(91)
                data.fill(0)
                data[1]=89
                data[5]=0
            } else {
                data=ByteArray(95)
                data.fill(0)
                data[1]=93
                data[5]=3
                data[91]=(diffMinuteUTC ushr 8).toByte() ; data[92]=diffMinuteUTC.toByte()
                data[93]=(diffMinuteDST ushr 8).toByte() ; data[94]=diffMinuteDST.toByte()
            }
            data[1]=0x5D
            data[2]=0x08 ; data[3]=0x02 ; data[4]=(0xFC).toByte()
            data[8]=0x10 ; data[9]=0x10; data[10]=0x10
            val lat=round(latitude*10000000).toInt()
            data[11]=(lat ushr 24).toByte() ; data[12]=(lat ushr 16).toByte()
            data[13]=(lat ushr 8).toByte() ; data[14]=lat.toByte()
            val lng=round(longitude*10000000).toInt()
            data[15]=(lng ushr 24).toByte() ; data[16]=(lng ushr 16).toByte()
            data[17]=(lng ushr 8).toByte() ; data[18]=lng.toByte()
            val calendarUTC = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendarUTC.timeInMillis=timeInMillis
            val year=calendarUTC.get(Calendar.YEAR)
            data[19]=(year ushr 8).toByte() ; data[20]=year.toByte()
            data[21]=(calendarUTC.get(Calendar.MONTH)+1).toByte()
            data[22]=calendarUTC.get(Calendar.DAY_OF_MONTH).toByte()
            data[23]=calendarUTC.get(Calendar.HOUR_OF_DAY).toByte()
            data[24]=calendarUTC.get(Calendar.MINUTE).toByte()
            data[25]=calendarUTC.get(Calendar.SECOND).toByte()
            return data
        }
        
        fun isTZAndDSTNeeded(conf21: ByteArray) : Boolean {
            return conf21.size>4 && (conf21[4].toInt() and 2)==2
        }
    }

    private var characteristicGeoData : BluetoothGattCharacteristic? =null
    private var characteristicGeo30   : BluetoothGattCharacteristic? =null
    private var characteristicGeo31   : BluetoothGattCharacteristic? =null
    private var characteristicGeoTime : BluetoothGattCharacteristic? =null
    private var characteristicGeoZone : BluetoothGattCharacteristic? =null
    private var characteristicRemote  : BluetoothGattCharacteristic? =null

    override suspend fun discovery(ble: BLE) : Boolean {
        val srvRemote = ble.gatt.getService(SERVICE_REMOTE)
        characteristicRemote = srvRemote?.getCharacteristic(CHAR_REMOTE_WRITE)
        val srvGeo = ble.gatt.getService(SERVICE_GEO)
        characteristicGeoData = srvGeo?.getCharacteristic(CHAR_GEO_DATA)
        characteristicGeo30 = srvGeo?.getCharacteristic(CHAR_GEO_SET30)
        characteristicGeo31 = srvGeo?.getCharacteristic(CHAR_GEO_SET31)
        characteristicGeoTime = srvGeo?.getCharacteristic(CHAR_GEO_SET_TIME)
        characteristicGeoZone = srvGeo?.getCharacteristic(CHAR_GEO_SET_ZONE)
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun config(ble: BLE): Boolean {
        return characteristicGeoData==null ||
            (  characteristicGeo30  ?.let{ble.write(it, GEO_ENABLE)==BluetoothGatt.GATT_SUCCESS}!=false
            && characteristicGeo31  ?.let{ble.write(it, GEO_ENABLE)==BluetoothGatt.GATT_SUCCESS}!=false
            && characteristicGeoTime?.let{ble.write(it, GEO_ENABLE)==BluetoothGatt.GATT_SUCCESS}!=false
            && characteristicGeoZone?.let{ble.write(it, GEO_ENABLE)==BluetoothGatt.GATT_SUCCESS}!=false)
    }

    override val remoteFeatures
        get() = if (characteristicRemote!=null) "|Wide|Tele|Focus|Shot|" else ""

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun geoTag(ble: BLE, location: Location, timeInMillis: Long): Boolean {
        return characteristicGeoData?.let{
            ble.write(it, makeGeoData(location.longitude, location.latitude, timeInMillis + 600), // make up for lost time
                )==BluetoothGatt.GATT_SUCCESS
        }!=false
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun remote(ble: BLE, feature: String, active: Boolean): Boolean {
        return characteristicRemote?.let { char ->
            (if (active) when (feature) {
                "Tele" -> REMOTE_TELE_DOWN
                "Wide" -> REMOTE_WIDE_DOWN
                "Focus" -> REMOTE_FOCUS_DOWN
                "Shot" -> REMOTE_SHOT_DOWN
                else -> null
            } else when (feature) {
                "Tele" -> REMOTE_TELE_UP
                "Wide" -> REMOTE_WIDE_UP
                "Focus" -> REMOTE_FOCUS_UP
                "Shot" -> REMOTE_SHOT_UP
                else -> null
            }) ?.let {
                ble.write(char, it)==BluetoothGatt.GATT_SUCCESS
            }
        } != false
    }
}