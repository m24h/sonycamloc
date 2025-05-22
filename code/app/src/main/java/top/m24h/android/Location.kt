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
open class Location (val context : Context) : LocationListener{
    var longitude: Double? = null
    var latitude: Double? = null
    var started = false

    private var manager : LocationManager? = null

    /**
     * called when location is updated
     */
    override fun onLocationChanged(location: Location) {
        longitude = location.longitude
        latitude = location.latitude
    }

    /**
     * is started?
     */
    fun isStarted() =started

    /**
     * stop locating
     */
    @Suppress("unused")
    fun stop() {
        if (started)  try {
            started=false
            manager?.removeUpdates(this)
        } catch (_: Exception) {}
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
        manager ?.run { if (isProviderEnabled(LocationManager.FUSED_PROVIDER) == true)
                            LocationManager.FUSED_PROVIDER
                        else if (isProviderEnabled(LocationManager.GPS_PROVIDER) == true)
                            LocationManager.GPS_PROVIDER
                        else null }
                    ?.let {
                        manager!!.requestLocationUpdates(it, interval, distance, this)
                        started=true
                    }
        return (started)
    }
}