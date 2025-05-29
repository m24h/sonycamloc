package top.m24h.android

import android.Manifest
import android.content.Context
import android.content.Context.LOCATION_SERVICE
import android.location.Location as AndroidLocation
import android.location.LocationListener
import android.location.LocationManager
import androidx.annotation.RequiresPermission

/**
 * a simple location implement
 */
class Location (val context : Context, val updater:((AndroidLocation)->Unit)?=null) : LocationListener {
    companion object {
        fun convertDMS(inp: Double?, positiveStr: String, negativeStr: String): String? {
            if (inp==null) return null
            if (inp<0) return convertDMS(-inp, negativeStr, negativeStr)
            var d=inp.toInt()
            var s=(inp-d)*60
            var m=s.toInt()
            s=(s-m)*60
            return "%d°%d'%.2f\"%s".format(d, m, s, positiveStr)
        }
    }

    var longitude: Double? = null
    var latitude: Double? = null
    var started = false

    private var manager : LocationManager? = null

    /**
     * called when location is updated
     */
    override fun onLocationChanged(location: AndroidLocation) {
        longitude = location.longitude
        latitude = location.latitude
        updater?.invoke(location)
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