package top.m24h.sonycamloc

import android.Manifest
import android.app.AlarmManager
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
import android.os.PowerManager
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
import kotlinx.coroutines.runBlocking
import top.m24h.android.Location
import java.text.DateFormat
import java.util.Date

class MainService : Service() , IBinder by Binder() {
    companion object {
        val broadcastAction = MainService::class.qualifiedName!!
    }
    // data maintained by others
    var cameraMAC:String? =null
    var locEnable =false
    // async jobs
    private val asyncScope = MainScope()
    private lateinit var loopActor : SendChannel<Unit>
    private var lastSyncTime : String? =null
    // for keeping alive
    private lateinit var alarmIntent : PendingIntent
    private lateinit var wakeLock : PowerManager.WakeLock
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
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
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
        // broadcast receiver
        registerReceiver(broadcastReceiver, broadcastFilter)
        // try to use alarm to keep alive
        alarmIntent = PendingIntent.getService(this, 0,
                    Intent(this, MainService::class.java).putExtra("type", "alarm"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(ALARM_SERVICE) as AlarmManager)
            .setRepeating(AlarmManager.RTC_WAKEUP , System.currentTimeMillis()+5000,
                resources.getInteger(R.integer.interval_ticker).toLong()*1000L, alarmIntent)
        // try to keep CPU alive when connected
        wakeLock=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK or PowerManager.LOCATION_MODE_NO_CHANGE,
                    "$packageName:wake")
        // try to start
        loopActor.trySend(Unit)
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDestroy() {
        if (wakeLock.isHeld) wakeLock.release()
        (getSystemService(ALARM_SERVICE) as AlarmManager?)?.cancel(alarmIntent)
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
        debug("on command")
        when (intent?.getStringExtra("type")) {
            "remote"-> {
                intent.getByteArrayExtra("remote")?.let {
                    if (gattConnected && characteristicRemote!=null) {
                        runBlocking { gattWrite(characteristicRemote!!, it) }
                    }
                }
                return START_STICKY
            }
            "stop"-> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (intent?.hasExtra("cameraMAC") == true) {
                    val cameraMACNew = intent.getStringExtra("cameraMAC")
                    if (cameraMAC!=cameraMACNew) {
                        gattClose()
                        cameraMAC=cameraMACNew
                    }
                }
                if (intent?.hasExtra("locEnable") == true) {
                    locEnable = intent.getBooleanExtra("locEnable", false)
                }
                loopActor.trySend(Unit)
                return START_STICKY
            }
        }
    }
    // wait for an non-null object
    private suspend fun <T> waitTimeout(timeoutMs:Int, waitWhenGot:T? =null, interval: Int =100, checker:()->T?) : T? {
        var timeLeft = (timeoutMs+interval/2)/interval
        while (true) {
            val ret=checker()
            if (ret==waitWhenGot) {
                if (--timeLeft<0) return waitWhenGot
                delay(interval.toLong())
            } else return ret
        }
    }
    // main loop
    @RequiresPermission(allOf=[Manifest.permission.BLUETOOTH_CONNECT,
                               Manifest.permission.ACCESS_FINE_LOCATION,
                               Manifest.permission.ACCESS_COARSE_LOCATION])
    private suspend fun loop() {
        // if gatt is not created, try to connect device
        if (gatt==null && cameraMAC?.isNotEmpty()==true) {
            (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager?)
                ?.adapter?.takeIf { it.isEnabled && it.state == BluetoothAdapter.STATE_ON }
                ?.getRemoteLeDevice(cameraMAC!!, BluetoothDevice.ADDRESS_TYPE_PUBLIC)
                ?.let {gatt=it.connectGatt(this, true, gattCallback, BluetoothDevice.TRANSPORT_LE)}
        }
        // check if location is needed to start
        if (gattConnected && locEnable) {
            if (!location.isStarted()) {
                if (location.start((resources.getInteger(R.integer.interval_location)).toLong() * 1000L, 0.0f)) {
                    waitTimeout(5000, false) {
                        location.latitude!=null && location.longitude!=null
                    }
                }
            }
            if (!wakeLock.isHeld) wakeLock.acquire(resources.getInteger(R.integer.wakelock_time).toLong()*1000L)
        } else if (location.isStarted()) {
            if (wakeLock.isHeld) wakeLock.release()
            location.stop()
        }
        // send GPS data to camera
        if (gattConnected && locEnable && characteristicGpsData!=null
            && location.latitude!=null && location.longitude!=null) {
            cameraInitialized = cameraInitialized || (
                    (characteristicGps30==null || gattWrite(characteristicGps30!!, SonyCam.GPS_ENABLE)==true)
                 && (characteristicGps31==null || gattWrite(characteristicGps31!!, SonyCam.GPS_ENABLE)==true)
                 && (characteristicGpsTime==null || gattWrite(characteristicGpsTime!!, SonyCam.GPS_ENABLE)==true))
            if (cameraInitialized &&
                gattWrite(characteristicGpsData!!, SonyCam.makeGPSData(
                    location.longitude!!,
                    location.latitude!!,
                    System.currentTimeMillis() + 600
                )) == true) {
                lastSyncTime = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
            } else { // retry
                delay(2000)
                loopActor.trySend(Unit)
            }
        }
        // send to main activity
        sendBroadcast(Intent(broadcastAction).apply {
            flags=Intent.FLAG_RECEIVER_REPLACE_PENDING or Intent.FLAG_RECEIVER_REGISTERED_ONLY
            setPackage(MainActivity::class.java.packageName)
            putExtra("connected", gattConnected)
            putExtra("canRemote", characteristicRemote!=null)
            putExtra("longitude", location.longitude?.toString())
            putExtra("latitude", location.latitude?.toString())
            putExtra("lastSyncTime", lastSyncTime)
        })
    }
    // location
    val location = object : Location(this) {
        override fun onLocationChanged(location: android.location.Location) {
            super.onLocationChanged(location)
            loopActor.trySend(Unit)
        }
    }
    // bluetooth
    var characteristicGpsData : BluetoothGattCharacteristic? =null
    var characteristicGps30 : BluetoothGattCharacteristic? =null
    var characteristicGps31 : BluetoothGattCharacteristic? =null
    var characteristicGpsTime : BluetoothGattCharacteristic? =null
    var characteristicGpsZone : BluetoothGattCharacteristic? =null
    var characteristicRemote : BluetoothGattCharacteristic? =null
    var gatt : BluetoothGatt? =null
    var gattConnected = false
    var cameraInitialized = false
    var gattStatusOk : Boolean? = null
    val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            gattStatusOk = status==BluetoothGatt.GATT_SUCCESS
        }
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServiceChanged(gatt: BluetoothGatt) {
            // The services used don't really change,
            // and sometimes connecting leads to redundant discoveries,
            // just after discoverServices() called each time when connected.
            // so ignore it
            super.onServiceChanged(gatt)
            /*
            if (false && gattConnected) { // only after connected
                gattConnected = false
                gatt.discoverServices()
            }
             */
        }
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val srvRemote = gatt.getService(SonyCam.SERVICE_REMOTE)
                characteristicRemote = srvRemote?.getCharacteristic(SonyCam.CHAR_REMOTE_WRITE)
                val srvGPS = gatt.getService(SonyCam.SERVICE_GPS)
                characteristicGpsData = srvGPS?.getCharacteristic(SonyCam.CHAR_GPS_DATA)
                characteristicGps30 = srvGPS?.getCharacteristic(SonyCam.CHAR_GPS_SET30)
                characteristicGps31 = srvGPS?.getCharacteristic(SonyCam.CHAR_GPS_SET31)
                characteristicGpsTime = srvGPS?.getCharacteristic(SonyCam.CHAR_GPS_SET_TIME)
                characteristicGpsZone = srvGPS?.getCharacteristic(SonyCam.CHAR_GPS_SET_TIME_ZONE)
                gattConnected=true
                cameraInitialized=false
                lastSyncTime=null
                loopActor.trySend(Unit)
            }
        }
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // call discoverServices() each time when connected,
                // it seems to reduce the time of wireless reconstruction.
                // Ignore discoverServices() and use cached characteristics, which takes more time.
                gatt.discoverServices()
            } else {
                gattConnected=false
                lastSyncTime=null
                loopActor.trySend(Unit)
            }
        }
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun gattClose() {
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
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun gattWrite(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) : Boolean? {
        gattStatusOk=null
        return if (gatt?.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                   ==BluetoothStatusCodes.SUCCESS)
                    // 20s is a suitable number to wait for wireless reconstruction,
                    // after which, communication is available
                    waitTimeout(20000) { gattStatusOk }
               else false
    }
}
