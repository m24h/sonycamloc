package top.m24h.sonycamloc

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import top.m24h.android.BLE
import top.m24h.android.Location
import java.text.DateFormat
import java.util.Date

private const val timeoutWrite=5000L
private const val timeoutMtuDiscovery=20000L
private const val timeoutFaith=2000L
private const val retryFaith=2
private const val timeoutToggleLocation=60000

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
                        BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> bleClose()
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
                    bleClose()
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
        bleClose()
        super.onDestroy()
    }
    // as bind service
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    // process commands
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("onStartCommand", "type : ${intent?.getStringExtra("type")}")
        when (intent?.getStringExtra("type")) {
            "remote"-> {
                intent.getByteArrayExtra("remote")?.let { bytes->
                    characteristicRemote?.let {
                        mainScope.launch(start=CoroutineStart.UNDISPATCHED) {
                            ble?.write(it, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, timeoutWrite)
                        }
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
                        bleClose()
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
    private var characteristicGpsData : BluetoothGattCharacteristic? =null
    private var characteristicGps30   : BluetoothGattCharacteristic? =null
    private var characteristicGps31   : BluetoothGattCharacteristic? =null
    private var characteristicGpsTime : BluetoothGattCharacteristic? =null
    private var characteristicGpsZone : BluetoothGattCharacteristic? =null
    private var characteristicRemote  : BluetoothGattCharacteristic? =null
    private var lastConnectedTime = 0L
    private var lastSyncTime : String? =null
    private var discovered = false
    private var mtuDone = false
    private var cameraInitialized = false
    private var retryCount = 0
    @RequiresPermission(allOf=[Manifest.permission.BLUETOOTH_CONNECT,
                               Manifest.permission.ACCESS_FINE_LOCATION,
                               Manifest.permission.ACCESS_COARSE_LOCATION])
    private suspend fun loop() {
        // if gatt is not created, try to connect device (auto-connect)
        ble=ble ?:
            cameraMAC?.takeIf { it.isNotEmpty() }
            ?.let {
                (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager?)
                    ?.adapter?.takeIf { it.isEnabled && it.state == BluetoothAdapter.STATE_ON }
                    ?.getRemoteDevice(it)
                    ?.let { BLE.open(this, it, true, Handler(mainLooper)) { connected ->
                        if (!connected) ble?.connect()
                        activeLoop()
                    } }
            }
        ble?.takeIf { it.isConnected } ?.also { ble ->
            if (!wakeLock.isHeld) wakeLock.acquire(resources.getInteger(R.integer.wakelock_time).toLong() * 1000L)
            joinAll(
                mainScope.launch {
                    // check if location is needed to start
                    if (locEnable && !location.isStarted()) {
                        if (location.start((resources.getInteger(R.integer.interval_location)).toLong() * 1000L, 0.0f, mainLooper)) {
                            location.waitForLocationData(timeoutWrite)
                        } else {
                            delay(3000L)
                            activeLoop() // try again
                        }
                    }
                },
                mainScope.launch {
                    // even if the operation fails, it does not affect the functionality
                    // on my Android 13 phone, normally BLE interval is 36, and for more than 200 discovery records, it takes about 9 seconds
                    // if MTU request can be done before underlying discovery, it takes only 3 seconds
                    // and sometimes timeout/reconnecting can cause Android to choose a smallest interval 6, it takes only 2 seconds
                    mtuDone = mtuDone || ble.requestMtu( 517, // 517 is from Android 14
                        if (faithMode && discovered && retryCount<retryFaith) timeoutFaith
                        else timeoutMtuDiscovery )!=null
                    if (!mtuDone) {
                        retryCount++
                        ble.disconnect()
                    } else {
                        retryCount=0
                    }
                    // discovery services
                    if (!discovered && ble.isConnected) {
                        if (ble.discoveryServices(timeoutMtuDiscovery) == BluetoothGatt.GATT_SUCCESS) {
                            val srvRemote = ble.gatt.getService(SonyCam.SERVICE_REMOTE)
                            characteristicRemote = srvRemote?.getCharacteristic(SonyCam.CHAR_REMOTE_WRITE)
                            val srvGPS = ble.gatt.getService(SonyCam.SERVICE_GPS)
                            characteristicGpsData = srvGPS?.getCharacteristic(SonyCam.CHAR_GPS_DATA)
                            characteristicGps30 = srvGPS?.getCharacteristic(SonyCam.CHAR_GPS_SET30)
                            characteristicGps31 = srvGPS?.getCharacteristic(SonyCam.CHAR_GPS_SET31)
                            characteristicGpsTime = srvGPS?.getCharacteristic(SonyCam.CHAR_GPS_SET_TIME)
                            characteristicGpsZone = srvGPS?.getCharacteristic(SonyCam.CHAR_GPS_SET_ZONE)
                            discovered=true
                        } else
                            ble.disconnect()
                    }
                    // initialize camera to receive location,
                    // Maybe this is not needed with the camera before the A7CR,
                    // but I only had the A7CR and tried to make compatible with the older cameras
                    if (!cameraInitialized  && ble.isConnected) {
                        cameraInitialized = characteristicGpsData==null ||
                                (    characteristicGps30  ?.let{ble.write(it, SonyCam.GPS_ENABLE, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, timeoutWrite)==BluetoothGatt.GATT_SUCCESS}!=false
                                  && characteristicGps31  ?.let{ble.write(it, SonyCam.GPS_ENABLE, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, timeoutWrite)==BluetoothGatt.GATT_SUCCESS}!=false
                                  && characteristicGpsTime?.let{ble.write(it, SonyCam.GPS_ENABLE, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, timeoutWrite)==BluetoothGatt.GATT_SUCCESS}!=false
                                  && characteristicGpsZone?.let{ble.write(it, SonyCam.GPS_DISABLE, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, timeoutWrite)==BluetoothGatt.GATT_SUCCESS}!=false)
                        if (!cameraInitialized) ble.disconnect()
                    }
                }
            )
            lastConnectedTime=System.currentTimeMillis()
            false
        }
        // if disconnected or closed
        if (ble?.isConnected!=true) {
            mtuDone = false
            discovered = discovered && ble!=null && faithMode
            cameraInitialized = false
            // not to toggle location off so rapidly since re-connecting may be needed
            // location updating/alarm intent/power key/... can trigger this after disconnected
            if (System.currentTimeMillis() - lastConnectedTime > timeoutToggleLocation ) {
                if (wakeLock.isHeld) wakeLock.release()
                if (location.isStarted()) location.stop()
                lastSyncTime = null
            }
        }
        // if location disabled
        if (!locEnable && location.isStarted()) {
            location.stop()
            lastSyncTime = null
        }
        // current location
        val loc=location.location
        // send GPS data to camera
        ble?.takeIf{it.isConnected && cameraInitialized && locEnable && loc!=null}?.also { ble ->
            if (characteristicGpsData?.let {
                ble.write(it,
                    SonyCam.makeGPSData(loc!!.longitude, loc.latitude, System.currentTimeMillis() + 600), // make up for lost time
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, timeoutWrite
                )} == BluetoothGatt.GATT_SUCCESS ){
                lastSyncTime = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
            }
        }
        // send to main activity
        sendBroadcast(Intent(broadcastAction).apply {
            flags=Intent.FLAG_RECEIVER_REPLACE_PENDING or Intent.FLAG_RECEIVER_REGISTERED_ONLY
            setPackage(MainActivity::class.java.packageName)
            putExtra("ready", cameraInitialized)
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
    private var ble : BLE? =null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun bleClose() {
        ble?.close()
        ble=null
        mtuDone=false
        cameraInitialized=false
        discovered=false
    }
}
