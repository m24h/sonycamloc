package top.m24h.sonycamloc

import android.location.Location
import android.util.SparseArray
import top.m24h.android.BLE

@Suppress("unused")
interface Camera {
    companion object Companion {
        val supportedCameraTypes = listOf(SonyCam, RicohCam)

        /**
         * get camera type by name, null if not found
         */
        fun type(name:String?) : CameraType? =
            name?.let { supportedCameraTypes.find { it.name==name } }

        /**
         * get camera type by manufacturer specified data in BLE advertisement, null if not supported
         */
        @JvmStatic
        fun type(manu:SparseArray<ByteArray>?) : CameraType? =
            manu ?.let {supportedCameraTypes.find { it.match(manu) } }

        /**
         *  get camera by connected bluetooth, null if not found
         */
        fun get(ble:BLE?) : Camera? =
            ble?.let { supportedCameraTypes.find { it.match(ble) } }?.camera()
    }

    /**
     * return '|' separated remote features like '|Wide|Tele|Focus|Shot|'
     */
    val remoteFeatures : String

    /**
     * discovery services/characteristics, maybe once only after first time connected
     */
    suspend fun discovery(ble: BLE) : Boolean

    /**
     * config after each time connected, after discovery and before any remote/location progress
     */
    suspend fun config(ble: BLE) : Boolean

    /**
     * send remote command, `active` is true when button is down, false when button is up
     */
    suspend fun remote(ble: BLE, feature:String, active:Boolean) : Boolean

    /**
     * send geo information to camera
     */
    suspend fun geoTag(ble: BLE, location:Location, timeInMillis:Long) :Boolean
}
