package top.m24h.android

import android.Manifest
import android.content.Context
import android.content.Context.LOCATION_SERVICE
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.annotation.RequiresPermission

/**
 * a simple location implement
 */
class Location (val context : Context) {
    var longitude: Double? = null
    var latitude: Double? = null
    private var manager : LocationManager? = null
    private var updater : LocationListener? = null

    /**
     * is it running
     */
    @Suppress("unused")
    fun isStarted (): Boolean {
        return updater!=null
    }

    /**
     * stop locating
     */
    @Suppress("unused")
    fun stop() {
        try {
            updater?.let { manager?.removeUpdates(it) }
        } catch (_: Exception) {}
        updater = null
        longitude = null
        latitude = null
    }

    /**
     * start locating
     */
    @Suppress("unused")
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION,
                                 Manifest.permission.ACCESS_COARSE_LOCATION])
    fun start(interval:Long, distance:Float) : Boolean {
        stop()
        manager = manager ?: (context.getSystemService(LOCATION_SERVICE) as LocationManager?)
        (if (manager?.isProviderEnabled(LocationManager.FUSED_PROVIDER) == true)
                LocationManager.FUSED_PROVIDER
            else if (manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true)
                LocationManager.GPS_PROVIDER
            else null
        ) ?.let {
            updater = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    longitude = location.longitude
                    latitude = location.latitude
                }
            }
            manager!!.requestLocationUpdates(it, interval, distance, updater!!)
        }
        return (updater!=null)
    }
}