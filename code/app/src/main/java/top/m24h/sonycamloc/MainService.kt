package top.m24h.sonycamloc

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.m24h.android.GattReadWrite

class MainService : Service() , IBinder by Binder() {
    companion object {
        val broadcastAction = MainService::class.qualifiedName!!
        val bluetoothTimeout = 15000L
    }
    // device scanning interval, real values will be got from resources table later
    var interval=20
    var intervalLazy=300
    // data maintained by me
    var longitude:Double? =null
    var latitude:Double? =null
    // data maintained by others
    var cameraMAC:String? =null
    var locEnable =false
    var lazy =false // it does not affect the operation of loopActor
    // bluetooth
    val gattCall=GattReadWrite ()
    var characteristicGPS:BluetoothGattCharacteristic?=null
    var characteristicGPS30:BluetoothGattCharacteristic?=null
    var characteristicGPS31:BluetoothGattCharacteristic?=null
    var characteristicRemote:BluetoothGattCharacteristic?=null
    // location
    var locationManager:LocationManager? =null
    var locationUpdate = false
    var locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            longitude = location.longitude
            latitude = location.latitude
        }
    }
    // async jobs
    private val asyncScope =MainScope()
    private lateinit var loopActor : SendChannel<Unit>
    private var loopCounter=0L
    // receive broadcast from others
    // message from others, should be register/unregister on create/destroy
    private val broadcastFilter=IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
    }
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loopActor.trySend(Unit)
        }
    }
    // foreground service functions
    private fun createNotifyChannel() {
        (application.getSystemService(NOTIFICATION_SERVICE) as NotificationManager?)
            ?.createNotificationChannel(
                NotificationChannel(
                    getString(R.string.channel_id),
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = getString(R.string.channel_description)
                }
            )
    }
    private fun runForeground() {
        try {
            startForeground (
                47,
                NotificationCompat.Builder(this, getString(R.string.channel_id))
                    .setSmallIcon(R.drawable.notify_icon)
                    .setContentText(getString(R.string.service_notify))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(PendingIntent.getActivity(
                        this,
                        System.currentTimeMillis().toInt(),
                        Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        },
                        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    .build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                        or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (e: Exception) {
            Log.e("MainService.runForeground", "Exception", e)
        }
    }
    // on create / destroy
    @OptIn(ObsoleteCoroutinesApi::class) // for Actor ReceiveChannel
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onCreate() {
        super.onCreate()
        // get setting from resource table
        interval=resources.getInteger(R.integer.interval)
        intervalLazy=resources.getInteger(R.integer.interval_lazy)
        // become foreground service
        createNotifyChannel()
        runForeground()
        // main working loop
        loopActor=asyncScope.actor<(Unit)>(capacity=1)  {
            while (isActive) {
                channel.receive()
                try {
                    loop()
                } catch (e:Exception) {
                    Log.e("MainService.onCreate", "Exception in loop()", e)
                    closeLocation()
                    gattCall.close()
                }
            }
        }
        // ticker to run loop() according to some interval
        asyncScope.launch {
            var tick=0
            while (isActive) {
                tick++
                if (tick>(if (lazy && gattCall.gatt==null) intervalLazy else interval)) {
                    tick=0
                    loopActor.trySend(Unit)
                }
                delay(1000)
            }
        }
        // broadcast receiver
        registerReceiver(broadcastReceiver, broadcastFilter)
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDestroy() {
        unregisterReceiver(broadcastReceiver)
        asyncScope.cancel()
        closeLocation()
        gattCall.close()
        super.onDestroy()
    }
    // as bind service
    override fun onBind(intent: Intent?): IBinder? {
        return this
    }
    // process commands
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra("type")) {
            "remote"-> {
                intent.getByteArrayExtra("remote")?.let {
                    gattCall.gatt?.takeIf{characteristicRemote!=null}
                        ?.writeCharacteristic(characteristicRemote!!, it, characteristicRemote!!.writeType)
                }
            }
            "stop"-> {
                stopSelf()
            }
            "start", "update" -> {
                if (intent.hasExtra("cameraMAC")) {
                    val cameraMACNew = intent.getStringExtra("cameraMAC")
                    if (cameraMAC!=cameraMACNew) {
                        gattCall.close()
                        cameraMAC=cameraMACNew
                    }
                }
                if (intent.hasExtra("locEnable")) {
                    locEnable = intent.getBooleanExtra("locEnable", false)
                }
                if (intent.hasExtra("lazy")) {
                    lazy = intent.getBooleanExtra("lazy", false)
                }
                loopActor.trySend(Unit)
            }
        }
        return START_STICKY
    }
    // location functions
    private fun closeLocation() {
        if (locationManager!=null && locationUpdate) {
            try {
                locationManager!!.removeUpdates(locationListener)
                locationUpdate = false
            } catch (_:Exception) {}
        }
        latitude=null
        longitude=null
    }
    // main loop
    @RequiresPermission(allOf=[Manifest.permission.BLUETOOTH_CONNECT,
                               Manifest.permission.ACCESS_FINE_LOCATION,
                               Manifest.permission.ACCESS_COARSE_LOCATION])
    private suspend fun loop() {
        loopCounter++
        debug("loop counter: $loopCounter")
        // if gatt is not created, try to connect device
        if (gattCall.gatt==null && cameraMAC?.isNotEmpty()==true) {
            val device = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager?)
                ?.adapter?.takeIf { it.isEnabled && it.state == BluetoothAdapter.STATE_ON }
                ?.getRemoteLeDevice(cameraMAC!!, BluetoothDevice.ADDRESS_TYPE_PUBLIC)
            if (device != null) {
                debug("connect")
                gattCall.suspendTimeoutClose (bluetoothTimeout) { cont ->
                    connect(device, this@MainService, cont)
                }
                debug(if (gattCall.gatt != null) "connected" else "not connected")
                if (gattCall.gatt != null) {
                    val srvRemote = gattCall.gatt!!.getService(SonyCam.SERVICE_REMOTE)
                    characteristicRemote = srvRemote?.getCharacteristic(SonyCam.CHAR_REMOTE_WRITE)
                    val srvGPS = gattCall.gatt!!.getService(SonyCam.SERVICE_GPS)
                    characteristicGPS = srvGPS?.getCharacteristic(SonyCam.CHAR_GPS_DATA)
                    characteristicGPS30 = srvGPS?.getCharacteristic(SonyCam.CHAR_GPS_SET30)
                    characteristicGPS31 = srvGPS?.getCharacteristic(SonyCam.CHAR_GPS_SET31)
                    debug("characteristics got")
                    characteristicGPS30?.let {
                        gattCall.suspendTimeoutClose (bluetoothTimeout) { cont ->
                            write(it, SonyCam.SET30_ENABLE, cont)
                        }
                        // twice for safe
                        gattCall.suspendTimeoutClose (bluetoothTimeout) { cont ->
                            write(it, SonyCam.SET30_ENABLE, cont)
                        }
                    }
                    debug("gps30 set")
                    characteristicGPS31?.let {
                        gattCall.suspendTimeoutClose (bluetoothTimeout) { cont ->
                            write(it, SonyCam.SET31_ENABLE, cont)
                        }
                        // twice for safe
                        gattCall.suspendTimeoutClose (bluetoothTimeout) { cont ->
                            write(it, SonyCam.SET31_ENABLE, cont)
                        }
                    }
                    debug("gps31 set")
                }
            }
        }
        // location
        if (gattCall.gatt!=null && locEnable) {
            if (!locationUpdate) {
                locationManager = locationManager
                    ?: (getSystemService(LOCATION_SERVICE) as LocationManager?)
                if (locationManager != null) {
                    (if (locationManager!!.isProviderEnabled(LocationManager.FUSED_PROVIDER) == true)
                        LocationManager.FUSED_PROVIDER
                    else if (locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER) == true)
                        LocationManager.GPS_PROVIDER
                    else null)?.let {
                        locationManager!!.requestLocationUpdates(
                            it, (interval - 2).toLong() * 1000L, 0.0f, locationListener
                        )
                        locationUpdate=true
                        // wait for first location data
                        for (i in 1..10) {
                            if (latitude!=null && longitude!=null)
                                break
                            delay(500)
                        }
                    }
                }
            }
        } else if (locationUpdate) {
            closeLocation()
        }
        // send to main activity
        sendBroadcast(Intent(broadcastAction).apply{
            flags=Intent.FLAG_RECEIVER_REPLACE_PENDING or Intent.FLAG_RECEIVER_REGISTERED_ONLY
            setPackage(MainActivity::class.java.packageName)
            putExtra("connected", gattCall.gatt!=null)
            putExtra("canRemote", characteristicRemote!=null)
            putExtra("longitude", longitude?.toString())
            putExtra("latitude", latitude?.toString())
            putExtra("loopCounter", loopCounter.toString())
        })
        // send GPS data to camera
        if (locEnable && gattCall.gatt!=null && longitude!=null && latitude!=null
            && characteristicGPS!=null) {
            gattCall.suspendTimeoutClose(bluetoothTimeout) { cont ->
                gattCall.write(
                    characteristicGPS!!,
                    SonyCam.makeGPSData(longitude!!, latitude!!, System.currentTimeMillis()),
                    cont
                )
            }
        }
    }

}
