package top.m24h.sonycamloc

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
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
import kotlinx.coroutines.runBlocking
import top.m24h.android.Location

class MainService : Service() , IBinder by Binder() {
    companion object {
        val broadcastAction = MainService::class.qualifiedName!!
    }
    // device scanning interval, real values will be got from resources table later
    var interval=20
    var intervalLazy=300
    // data maintained by others
    var cameraMAC:String? =null
    var locEnable =false
    var lazy =false
    // async jobs
    private val asyncScope = MainScope()
    private lateinit var loopActor : SendChannel<Unit>
    private var loopCounter =0L
    // receive message from others, should be register/unregister on create/destroy
    private val broadcastFilter=IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
    }
    private val broadcastReceiver = object : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    // if bluetooth is turned off, new manual connection is needed for an auto-connect gatt
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)) {
                        BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> gattClose()
                    }
                }
            }
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
                    location.stop()
                    gattClose()
                }
            }
        }
        // ticker to run loop() according to some interval
        asyncScope.launch {
            var lastTime=0L
            while (isActive) {
                var now=System.currentTimeMillis()
                if (((now-lastTime)/1000)>(if (lazy && !gattConnected) intervalLazy else interval)) {
                    lastTime=now
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
        location.stop()
        gattClose()
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
                    if (gatt!=null && characteristicRemote!=null) {
                        //       ?.writeCharacteristic(characteristicRemote!!, it, characteristicRemote!!.writeType)
                        runBlocking { gattWrite(characteristicRemote!!, it) }
                    }
                }
            }
            "stop"-> {
                stopSelf()
            }
            "start", "update" -> {
                if (intent.hasExtra("cameraMAC")) {
                    val cameraMACNew = intent.getStringExtra("cameraMAC")
                    if (cameraMAC!=cameraMACNew) {
                        gattClose()
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

    // main loop
    @RequiresPermission(allOf=[Manifest.permission.BLUETOOTH_CONNECT,
                               Manifest.permission.ACCESS_FINE_LOCATION,
                               Manifest.permission.ACCESS_COARSE_LOCATION])
    private suspend fun loop() {
        loopCounter++
        debug("loop counter: $loopCounter")
        // if gatt is not created, try to connect device
        if (gatt==null && cameraMAC?.isNotEmpty()==true) {
            (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager?)
                ?.adapter?.takeIf { it.isEnabled && it.state == BluetoothAdapter.STATE_ON }
                ?.getRemoteLeDevice(cameraMAC!!, BluetoothDevice.ADDRESS_TYPE_PUBLIC)
                ?.let{gatt=it.connectGatt(this, true, gattCallback)}
        }
        // check if location is needed to start
        if (gattConnected && locEnable && characteristicGpsData!=null) {
            if (!location.isStarted()) {
                if (location.start((interval - 2).toLong() * 1000L, 0.0f)) {
                    for (i in 1..30) {
                        if (location.latitude != null && location.longitude != null)
                            break
                        delay(200)
                    }
                }
            }
        } else if (location.isStarted()) {
            location.stop()
        }
        // send to main activity
        sendBroadcast(Intent(broadcastAction).apply {
            flags=Intent.FLAG_RECEIVER_REPLACE_PENDING or Intent.FLAG_RECEIVER_REGISTERED_ONLY
            setPackage(MainActivity::class.java.packageName)
            putExtra("connected", gattConnected)
            putExtra("canRemote", characteristicRemote!=null)
            putExtra("longitude", location.longitude?.toString())
            putExtra("latitude", location.latitude?.toString())
            putExtra("loopCounter", loopCounter.toString())
        })
        // send GPS data to camera
        if (locEnable && characteristicGpsData!=null && location.latitude!=null && location.longitude!=null) {
            // even when isConnected is false, just try
            if (!camInitialized) {
                delay(200)
                characteristicGps30?.let { gattWrite(it, SonyCam.GPS_ENABLE) }
                delay(200)
                characteristicGps31?.let { gattWrite(it, SonyCam.GPS_ENABLE) }
                delay(200)
                characteristicGpsTime?.let { gattWrite(it, SonyCam.GPS_ENABLE) }
                delay(200)
                camInitialized = gattConnected
            }
            gattWrite(characteristicGpsData!!,
                SonyCam.makeGPSData(location.longitude!!, location.latitude!!, System.currentTimeMillis()+600)
            )
        }
    }

    // bluetooth
    var characteristicGpsConf : BluetoothGattCharacteristic? =null
    var characteristicGpsData : BluetoothGattCharacteristic? =null
    var characteristicGps30 : BluetoothGattCharacteristic? =null
    var characteristicGps31 : BluetoothGattCharacteristic? =null
    var characteristicGpsTime : BluetoothGattCharacteristic? =null
    var characteristicGpsZone : BluetoothGattCharacteristic? =null
    var characteristicRemote : BluetoothGattCharacteristic? =null
    var gatt : BluetoothGatt? =null
    var gattConnected = false
    var camInitialized = false
    var gattWriteOk = false
    val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (status == BluetoothGatt.GATT_SUCCESS)
                gattWriteOk = true
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServiceChanged(gatt: BluetoothGatt) {
            super.onServiceChanged(gatt)
            gatt.discoverServices()
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val srvRemote = gatt.getService(SonyCam.SERVICE_REMOTE)
                characteristicRemote = srvRemote?.getCharacteristic(SonyCam.CHAR_REMOTE_WRITE)
                val srvGPS = gatt.getService(SonyCam.SERVICE_GPS)
                characteristicGpsConf = srvGPS?.getCharacteristic(SonyCam.CHAR_GPS_CONF)
                characteristicGpsData = srvGPS?.getCharacteristic(SonyCam.CHAR_GPS_DATA)
                characteristicGps30 = srvGPS?.getCharacteristic(SonyCam.CHAR_GPS_SET30)
                characteristicGps31 = srvGPS?.getCharacteristic(SonyCam.CHAR_GPS_SET31)
                characteristicGpsTime = srvGPS?.getCharacteristic(SonyCam.CHAR_GPS_SET_TIME)
                characteristicGpsZone = srvGPS?.getCharacteristic(SonyCam.CHAR_GPS_SET_TIME_ZONE)
                camInitialized=false
                gattConnected=true
                loopActor.trySend(Unit)
            }
        }
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else {
                gattConnected=false
                camInitialized=false
                loopActor.trySend(Unit)
            }
        }
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun gattClose() {
        characteristicGpsConf=null
        characteristicGpsData=null
        characteristicGps30=null
        characteristicGps31=null
        characteristicGpsTime=null
        characteristicGpsZone=null
        characteristicRemote=null
        try {
            gatt?.close()
        } catch (_:Exception) {}
        gatt=null
        gattConnected=false
        camInitialized=false
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun gattWrite(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        gattWriteOk=false
        if (gatt?.writeCharacteristic(characteristic, bytes, characteristic.writeType)==BluetoothStatusCodes.SUCCESS) {
            for (i in 0..50) {
                if (gattWriteOk)
                    break
                delay(100)
            }
        }
    }

    // location
    val location = Location(this)
}
