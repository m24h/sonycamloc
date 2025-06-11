package top.m24h.android

import android.Manifest
import android.content.Context
import android.content.Context.LOCATION_SERVICE
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Handler
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import android.location.Location as AndroidLocation

/**
 * a simple location implement
 */
class Location (val context : Context, val updater:((Location)->Unit)?=null) : LocationListener {
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

    var location: AndroidLocation? = null
    var updateTime:Long? = null

    /**
     * called when location is updated
     */
    override fun onLocationChanged(loc: AndroidLocation) {
        location=loc
        updateTime=System.currentTimeMillis()
        updater?.invoke(this)
    }

    /**
     * is started?
     */
    private var started = false
    fun isStarted() =started

    var manager: LocationManager?=null
    var handler: Handler? =null

    /**
     * stop locating
     */
    @Suppress("unused")
    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        if (started)  try { manager?.removeUpdates(this) } catch (_: Exception) {}
        started=false
        location = null
        updateTime = null
    }

    /**
     * start locating
     * If it `timeout` (more than `interval`+`timeout` since last `this.updateTime`) and does not get location, it will be restarted automatically,
     */
    @Suppress("unused")
    fun start(interval:Long, timeout:Long, distance:Float=0.0f) : Boolean {
        stop()
        manager = manager ?: context.getSystemService(LOCATION_SERVICE) as LocationManager?
        handler = handler ?: Handler(context.mainLooper)
        val request=LocationRequest.Builder(interval)
            .setMinUpdateDistanceMeters(distance)
            .setMinUpdateIntervalMillis(interval)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .build()
        updateTime = null
        started=handler?.post(object : Runnable {
            @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION])
            override fun run() {
                if (System.currentTimeMillis() - (updateTime?:0) >interval+timeout) try {
                    manager?.run {
                        (if (isProviderEnabled(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER
                        else if (isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER
                        else null) ?.let {
                            try { removeUpdates(this@Location) } catch (e: Exception) {}
                            requestLocationUpdates(it, request, context.mainExecutor, this@Location)
                        }
                    }
                } catch (e:Exception) {
                    Log.e("Location.start","failed to start location", e)
                }
                if (started) handler?.postDelayed(this, interval)
            }}) == true
        return (started)
    }

    /**
     * wait for non-null location
     */
    @Suppress("unused")
    suspend fun waitForLocationData(timeoutMs:Long, checkInterval: Long=100L) {
        var timeLeft = (timeoutMs+checkInterval/2)/checkInterval
        while (location==null && timeLeft-->0) delay(checkInterval)
    }

    @Suppress("unused")
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION])
    suspend fun getCurrent(interval:Long, timeout:Long) : AndroidLocation? = suspendCoroutine { cont ->
        manager = manager ?: (context.getSystemService(LOCATION_SERVICE) as LocationManager?)
        manager ?.run {
            (if (isProviderEnabled(LocationManager.FUSED_PROVIDER))
                LocationManager.FUSED_PROVIDER
            else if (isProviderEnabled(LocationManager.GPS_PROVIDER))
                LocationManager.GPS_PROVIDER
            else null)
                ?.let {
                    getCurrentLocation(it,
                        LocationRequest.Builder(interval)
                            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                            .setDurationMillis(timeout)
                            .build(),
                        null, context.mainExecutor) { cont.resume(it) }
                }
        }
    }
}