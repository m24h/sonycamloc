package top.m24h.sonycamloc

import android.util.SparseArray
import top.m24h.android.BLE

/**
 * each subclass of Camera should have a companion object of this type
 */
@Suppress("unused")
interface CameraType {
    /**
     * check if it's a camera of this type by manufacturer specified data in BLE advertisement
     */
    fun match(manu: SparseArray<ByteArray>): Boolean

    /**
     * check if it's a camera of this type by connected bluetooth
     */
    fun match(ble:BLE): Boolean

    /**
     * name of camera type, like "Sony"/"Ricoh"
     */
    val name : String

    /**
     * does it need to pair before use
     */
    val needBind : Boolean

    /**
     * get class of camera
     */
    fun cameraClass(): Class<out Camera>

    /**
     * create an camera instance of connected bluetooth,
     */
    fun camera(): Camera = cameraClass().getDeclaredConstructor().newInstance()
}