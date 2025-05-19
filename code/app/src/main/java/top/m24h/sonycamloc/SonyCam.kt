package top.m24h.sonycamloc

import android.util.SparseArray
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import kotlin.math.round

// only public static methods
class SonyCam { companion object {
    @Suppress("unused")
    @JvmField
    val SERVICE_GPS=UUID.fromString("8000DD00-DD00-FFFF-FFFF-FFFFFFFFFFFF")!!
    @Suppress("unused")
    @JvmField
    val CHAR_GPS_CONF21=UUID.fromString("0000DD21-0000-1000-8000-00805F9B34FB")!!
    @Suppress("unused")
    @JvmField
    val CHAR_GPS_DATA=UUID.fromString("0000DD11-0000-1000-8000-00805F9B34FB")!!
    @Suppress("unused")
    @JvmField
    // GPS enable
    val CHAR_GPS_SET30=UUID.fromString("0000DD30-0000-1000-8000-00805F9B34FB")!!
    @Suppress("unused")
    @JvmField
    // GPS enable
    val CHAR_GPS_SET31=UUID.fromString("0000DD31-0000-1000-8000-00805F9B34FB")!!
    @Suppress("unused")
    @JvmField
    // GPS Time enable
    val CHAR_GPS_SET32=UUID.fromString("0000DD32-0000-1000-8000-00805F9B34FB")!!
    @Suppress("unused")
    @JvmField
    // TimeZone/DST enable
    val CHAR_GPS_SET33=UUID.fromString("0000DD33-0000-1000-8000-00805F9B34FB")!!
    @Suppress("unused")
    @JvmField
    val SERVICE_REMOTE=UUID.fromString("8000FF00-FF00-FFFF-FFFF-FFFFFFFFFFFF")!!
    @Suppress("unused")
    @JvmField
    val CHAR_REMOTE_WRITE=UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")!!
    @Suppress("unused")
    @JvmField
    val SET3033_ENABLE=byteArrayOf(1)
    @Suppress("unused")
    @JvmField
    val REMOTE_FOCUS_DOWN=byteArrayOf(1,7)
    @Suppress("unused")
    @JvmField
    val REMOTE_FOCUS_UP=byteArrayOf(1,6)
    @Suppress("unused")
    @JvmField
    val REMOTE_SHOT_DOWN=byteArrayOf(1,9)
    @Suppress("unused")
    @JvmField
    val REMOTE_SHOT_UP=byteArrayOf(1,8)
    @Suppress("unused")
    @JvmField
    val REMOTE_TELE_DOWN=byteArrayOf(2,0x45,0x10)
    @Suppress("unused")
    @JvmField
    val REMOTE_TELE_UP=byteArrayOf(2,0x44,0)
    @Suppress("unused")
    @JvmField
    val REMOTE_WIDE_DOWN=byteArrayOf(2,0x47,0x10)
    @Suppress("unused")
    @JvmField
    val REMOTE_WIDE_UP=byteArrayOf(2,0x46,0)
    @Suppress("unused")
    @JvmField
    val calendarUTC:Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    @Suppress("unused")
    @JvmStatic
    fun isSonyCam(manu:SparseArray<ByteArray>?):Boolean {
        return (manu?.get(301)?.let { it.size>1 && it[0].toInt() == 3 && it[1].toInt() == 0 }) == true
    }
    @Suppress("unused")
    @JvmStatic
    fun makeGPSData(longitude:Double, latitude:Double, timeInMillis:Long, diffMinuteUTC:Int? =null, diffMinuteDST:Int? =null):ByteArray {
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
            data[91]=(diffMinuteUTC shr 8).toByte() ; data[92]=diffMinuteUTC.toByte()
            data[93]=(diffMinuteDST shr 8).toByte() ; data[94]=diffMinuteDST.toByte()
        }
        data[1]=0x5D
        data[2]=0x08 ; data[3]=0x02 ; data[4]=(0xFC).toByte()
        data[8]=0x10 ; data[9]=0x10; data[10]=0x10
        val lat=round(latitude*10000000).toInt()
        data[11]=(lat shr 24).toByte() ; data[12]=(lat shr 16).toByte()
        data[13]=(lat shr 8).toByte() ; data[14]=lat.toByte()
        val lng=round(longitude*10000000).toInt()
        data[15]=(lng shr 24).toByte() ; data[16]=(lng shr 16).toByte()
        data[17]=(lng shr 8).toByte() ; data[18]=lng.toByte()
        calendarUTC.timeInMillis=timeInMillis
        val year=calendarUTC.get(Calendar.YEAR)
        data[19]=(year shr 8).toByte() ; data[20]=year.toByte()
        data[21]=(calendarUTC.get(Calendar.MONTH)+1).toByte()
        data[22]=calendarUTC.get(Calendar.DAY_OF_MONTH).toByte()
        data[23]=calendarUTC.get(Calendar.HOUR_OF_DAY).toByte()
        data[24]=calendarUTC.get(Calendar.MINUTE).toByte()
        data[25]=calendarUTC.get(Calendar.SECOND).toByte()
        return data
    }
    @Suppress("unused")
    @JvmStatic
    fun isTZAndDSTNeeded(set21: ByteArray) : Boolean {
        return set21.size>4 && (set21[4].toInt() and 2)==2
    }
}}