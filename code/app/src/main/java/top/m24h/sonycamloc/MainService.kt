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
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import top.m24h.android.Location
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.resume

class MainService : Service() {
    companion object {
        val broadcastAction = MainService::class.qualifiedName!!
    }
    // data maintained by others
    var cameraMAC:String? =null
    var locEnable =false
    var faithMode =false
    // async jobs
    private val mainScope = MainScope()
    private lateinit var loopActor : SendChannel<Unit>
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
                    // if bluetooth is turned off, close current connection
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)) {
                        BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> gattClose()
                    }
                }
            }
            activeLoop()
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
        loopActor=mainScope.actor<(Unit)>(capacity=1)  {
            while (isActive) {
                channel.receive()
                try {
                    loop()
                } catch (e:Exception) {
                    Log.e("MainService.onCreate", "Exception in loop()", e)
                    location.stop()
                    gattClose()
                    delay(2000)
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
        activeLoop()
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDestroy() {
        if (wakeLock.isHeld) wakeLock.release()
        (getSystemService(ALARM_SERVICE) as AlarmManager?)?.cancel(alarmIntent)
        unregisterReceiver(broadcastReceiver)
        mainScope.cancel()
        location.stop()
        gattClose()
        super.onDestroy()
    }
    // as bind service
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    // process commands
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra("type")) {
            "remote"-> {
                intent.getByteArrayExtra("remote")?.let { bytes->
                    characteristicRemote?.let {
                        mainScope.launch(start=CoroutineStart.UNDISPATCHED) { gattWrite(it, bytes) }
                    }
                }
            }
            "stop"-> {
                stopSelf()
                return START_NOT_STICKY
            }
            "start", "update" -> {
                if (intent.hasExtra("cameraMAC") == true) {
                    val cameraMACNew = intent.getStringExtra("cameraMAC")
                    if (cameraMAC!=cameraMACNew) {
                        gattClose()
                        cameraMAC=cameraMACNew
                    }
                }
                locEnable = intent.getBooleanExtra("locEnable", locEnable)
                faithMode = intent.getBooleanExtra("faithMode", faithMode)
                activeLoop()
            }
            "alarm" -> activeLoop()
        }
        return START_STICKY
    }
    // main loop
    @RequiresPermission(allOf=[Manifest.permission.BLUETOOTH_CONNECT,
                               Manifest.permission.ACCESS_FINE_LOCATION,
                               Manifest.permission.ACCESS_COARSE_LOCATION])
    private suspend fun loop() {
        // if gatt is not created, try to connect device (auto-connect)
        gatt=gatt ?:
            cameraMAC?.takeIf { it.isNotEmpty() }
            ?.let {
                (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager?)
                    ?.adapter?.takeIf { it.isEnabled && it.state == BluetoothAdapter.STATE_ON }
                    ?.getRemoteDevice(it)
                    ?.connectGatt(this, true, gattCallback,
                        BluetoothDevice.TRANSPORT_AUTO, BluetoothDevice.PHY_LE_1M_MASK,
                        Handler(mainLooper))  // uses main thread for volatile data
            }
        // initialize camera after connected
        if (gattConnected && locEnable && characteristicGpsData!=null && !cameraInitialized) {
            cameraInitialized =
                    characteristicGps30?.let{gattWrite(it, SonyCam.GPS_ENABLE)==true}!=false
                 && characteristicGps31?.let{gattWrite(it, SonyCam.GPS_ENABLE)==true}!=false
                 && characteristicGpsTime?.let{gattWrite(it, SonyCam.GPS_ENABLE)==true}!=false
                 && characteristicGpsZone?.let{gattWrite(it, SonyCam.GPS_DISABLE)==true}!=false
        }
        // check if location is needed to start
        if (gattConnected && locEnable) {
            if (!location.isStarted()) {
                if (location.start((resources.getInteger(R.integer.interval_location)).toLong() * 1000L, 0.0f, mainLooper)) {
                    if (!wakeLock.isHeld) wakeLock.acquire(resources.getInteger(R.integer.wakelock_time).toLong() * 1000L)
                    location.waitForLocationData(5000L)
                }
            }
        } else  {
            if (wakeLock.isHeld) wakeLock.release()
            if (location.isStarted()) location.stop()
        }
        // send GPS data to camera
        val loc = location.location // a copy to avoid volatile data
        if (gattConnected && locEnable && cameraInitialized && loc!=null) {
            if (characteristicGpsData?.let{
                gattWrite(it, SonyCam.makeGPSData(
                        loc.longitude, loc.latitude,
                        System.currentTimeMillis() + 600 // make up for lost time
                    )) } == true) {
                lastSyncTime = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
            }
        }
        // send to main activity
        sendBroadcast(Intent(broadcastAction).apply {
            flags=Intent.FLAG_RECEIVER_REPLACE_PENDING or Intent.FLAG_RECEIVER_REGISTERED_ONLY
            setPackage(MainActivity::class.java.packageName)
            putExtra("connected", gattConnected)
            putExtra("canRemote", characteristicRemote!=null)
            putExtra("longitude", Location.convertDMS(loc?.longitude, " E", " W"))
            putExtra("latitude", Location.convertDMS(loc?.latitude, " N", " S"))
            putExtra("lastSyncTime", lastSyncTime)
        })
    }
    // send to loopActor to active loop()
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun activeLoop() {
        if (!loopActor.isClosedForSend) loopActor.trySend(Unit)
    }
    // location
    private val location = Location(this) { activeLoop() }
    // bluetooth
    private var cameraInitialized = false
    private var lastSyncTime : String? =null
    private var characteristicGpsData : BluetoothGattCharacteristic? =null
    private var characteristicGps30 : BluetoothGattCharacteristic? =null
    private var characteristicGps31 : BluetoothGattCharacteristic? =null
    private var characteristicGpsTime : BluetoothGattCharacteristic? =null
    private var characteristicGpsZone : BluetoothGattCharacteristic? =null
    private var characteristicRemote : BluetoothGattCharacteristic? =null
    private var gatt : BluetoothGatt? =null
    private var gattConnected = false
    private var gattStatus : CancellableContinuation<Boolean>? = null
    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            gattStatus?.resume(status==BluetoothGatt.GATT_SUCCESS)
            gattStatus=null
        }
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServiceChanged(gatt: BluetoothGatt) {
            super.onServiceChanged(gatt)
            // The services used don't really change,
            // and sometimes connecting leads to redundant discoveries,
            // just after discoverServices() called each time when connected.
            // The Android underlay also does the discovery.
            // so ignore it
            /*
            if (faithMode && gattConnected) { // only after connected
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
                characteristicGpsZone = srvGPS?.getCharacteristic(SonyCam.CHAR_GPS_SET_ZONE)
                gattReset(true)
                activeLoop()
            } else { // try to re-connect
                gatt.disconnect()
                gatt.connect()
            }
        }
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // There is an uncontrollable services cache backend, sometimes fast and sometimes slow,
                // and seems to be related to the Different Transaction Collision issues in my phone.
                // It's safe to call discoverServices() each time when connected,
                // although if the discovering is really carried out, it is highly likely to trigger DTC.
                // But sometimes taking the risk of using the cache can be faster,
                // if service discovery did not happen in backend.
                if (faithMode && characteristicGpsData!=null) {
                    gattReset(true)
                    activeLoop()
                } else {
                    gatt.discoverServices()
                }
            } else {
                gattReset(false)
                activeLoop()
                //gatt.javaClass.getMethod("refresh")?.invoke(gatt)
            }
        }
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun gattClose() {
        gattReset(false)
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
    }
    private fun gattReset(connected:Boolean) {
        gattConnected=connected
        gattStatus=null
        cameraInitialized=false
        lastSyncTime=null
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun gattWrite(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) : Boolean? {
        return withTimeoutOrNull (if (faithMode) 2000L else 20000L) {
            while (gattStatus!=null) delay(100)
            suspendCancellableCoroutine<Boolean> { cont ->
                cont.invokeOnCancellation {
                    gattStatus=null
                    Log.e("MainService.gattWrite", "timeout, try to reconnect")
                    // this will break discovery progress in backend for faith mode, and speed up the response
                    gatt?.disconnect()
                    gatt?.connect()
                }
                gattStatus=cont
                if (gatt?.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    !=BluetoothStatusCodes.SUCCESS) {
                    gattStatus?.resume(false)
                    gattStatus=null
                }
            }
        }
    }
}
