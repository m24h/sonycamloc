package top.m24h.sonycamloc

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
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
import top.m24h.android.Location
import top.m24h.android.resourceLoader
import java.text.DateFormat
import java.util.Date

private const val CAMERA_SLOTS = 3

private const val NOTIFY_ID=47
private const val TIMEOUT_TOGGLE_LOCATION=60000L
private const val DELAY_FAILURE=10000L

class MainService : Service() {
    companion object {
        val broadcastAction = MainService::class.qualifiedName!!
    }

    // setting from resources
    val interval_location   :Int    by resourceLoader()
    val timeout_location    :Int    by resourceLoader()
    val interval_ticker     :Int    by resourceLoader()
    val wakelock_time       :Int    by resourceLoader()
    val channel_id          :String by resourceLoader()
    val channel_name        :String by resourceLoader()
    val channel_description :String by resourceLoader()
    val service_notify      :String by resourceLoader()

    // async jobs
    private val mainScope = MainScope()
    private lateinit var loopActor : SendChannel<Unit>
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun activeLoop()
            = loopActor.trySend(Unit)

    // camera slots, status notify will active main loop
    val cameras = List(CAMERA_SLOTS) { CameraSlot(this, mainScope) { activeLoop() } }

    // location provider
    private var locationOrder = 0
    private val location = Location(this) {
        if (locationOrder<Int.MAX_VALUE) locationOrder++
        // geo-tag using current location
        for (cam in cameras) cam.setLocation(it.location, locationOrder)
        activeLoop()
    }

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
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON)) {
                    BluetoothAdapter.STATE_TURNING_OFF -> for (cam in cameras) cam.reset()
                    BluetoothAdapter.STATE_ON -> for (cam in cameras) cam.activeLoop()
                }
                LocationManager.PROVIDERS_CHANGED_ACTION -> activeLoop()
                else -> {
                    for (cam in cameras) cam.activeLoop()
                    activeLoop()
                }
            }
        }
    }

    // foreground service functions
    private fun createNotifyChannel() {
        (application.getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(
                NotificationChannel(
                    channel_id,
                    channel_name,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = channel_description
                }
            )
    }
    private fun runForeground() {
        startForeground (
            NOTIFY_ID,
            NotificationCompat.Builder(this, channel_id)
                .setSmallIcon(R.drawable.notify_icon)
                .setContentText(service_notify)
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
                try {
                    if (channel.receiveCatching().getOrNull()==null) break
                    loop()
                    delay(1000L) // merge frequent events
                } catch (_:CancellationException) {
                    break
                } catch (e:Exception) {
                    Log.e("MainService.onCreate", "Exception in loop()", e)
                    location.stop()
                    delay(DELAY_FAILURE)
                    activeLoop()
                }
            }
        }
        // broadcast receiver
        registerReceiver(broadcastReceiver, broadcastFilter)
        // try to use alarm to keep alive
        alarmIntent = PendingIntent.getService(this, 0,
            Intent(this, javaClass).putExtra("type", "alarm"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(ALARM_SERVICE) as AlarmManager)
            .setRepeating(AlarmManager.RTC_WAKEUP , System.currentTimeMillis()+5000,
                interval_ticker*1000L, alarmIntent)
        // try to keep CPU alive when connected
        wakeLock=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.LOCATION_MODE_NO_CHANGE,
            "$packageName:wake")
        // start camera slot async job
        for (cam in cameras) cam.start()
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDestroy() {
        if (wakeLock.isHeld) wakeLock.release()
        (getSystemService(ALARM_SERVICE) as AlarmManager).cancel(alarmIntent)
        unregisterReceiver(broadcastReceiver)
        location.stop()
        for (cam in cameras) cam.stop()
        mainScope.cancel()
        super.onDestroy()
    }

    // should not be used as bind service
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    // process commands
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra("type")) {
            "remote"-> {
                val slot=intent.getIntExtra("slot", 0)
                val remote=intent.getStringExtra("remote")?:""
                val active=intent.getBooleanExtra("active", false)
                if (slot>=0 && slot<CAMERA_SLOTS) mainScope.launch(start=CoroutineStart.UNDISPATCHED) {
                    cameras[slot].remote(remote, active)
                }
            }
            "stop"-> {
                stopSelf()
                return START_NOT_STICKY
            }
            "start", "update" -> {
                if (intent.hasExtra("locEnable")) {
                    val locEnable = intent.getBooleanExtra("locEnable", false)
                    for (cam in cameras) cam.locEnable = locEnable
                }
                if (intent.hasExtra("faithMode")) {
                    val faithMode = intent.getIntExtra("faithMode", 1)
                    for (cam in cameras) cam.faithMode = faithMode
                }
                for (i in 0..CAMERA_SLOTS-1) {
                    if (intent.hasExtra("cameraMac$i"))
                        cameras[i].setMac(intent.getStringExtra("cameraMac$i"), intent.getStringExtra("cameraClass$i"))
                }
                activeLoop()
            }
            "alarm" -> {
                for (cam in cameras) cam.activeLoop()
                activeLoop()
            }
        }
        return START_STICKY
    }

    // main loop
    private var lastConnectedTime =0L
    @RequiresPermission(allOf=[Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION])
    private suspend fun loop() {
        if (cameras.any { it.isConnected }) {
            lastConnectedTime = System.currentTimeMillis()
            if (!wakeLock.isHeld) wakeLock.acquire(wakelock_time * 1000L)
            if (cameras.any { it.locEnable } && !location.isStarted) {
                if (!location.start(interval_location * 1000L, timeout_location * 1000L)) {
                    delay(DELAY_FAILURE)
                    activeLoop() // try again
                }
            }
        } else {
            // not to toggle location off so rapidly since re-connecting may be needed
            // location updating/alarm intent/power key/... can trigger this after disconnected
            if (System.currentTimeMillis() - lastConnectedTime > TIMEOUT_TOGGLE_LOCATION) {
                if (wakeLock.isHeld) wakeLock.release()
                if (location.isStarted) location.stop()
                locationOrder=0
            }
        }

        // send current status to main activity
        sendBroadcast(Intent(broadcastAction).apply {
            flags=Intent.FLAG_RECEIVER_REPLACE_PENDING or Intent.FLAG_RECEIVER_REGISTERED_ONLY
            setPackage(MainActivity::class.java.packageName)
            for (i in 0..CAMERA_SLOTS-1) {
                putExtra("ready$i", cameras[i].ready)
                putExtra("remoteFeatures$i", cameras[i].remoteFeatures)
            }
            val loc=location.location?.takeIf {cameras.any {it.locEnable} }
            putExtra("longitude", Location.convertDMS(loc?.longitude, " E", " W"))
            putExtra("latitude", Location.convertDMS(loc?.latitude, " N", " S"))
            putExtra("altitude", loc?.takeIf{it.hasAltitude()}?.altitude?.let{"%.2f".format(it)})
            val lastSyncTime=cameras.maxOf { it.lastSyncTime ?: 0L}
            putExtra("lastSyncTime", if (lastSyncTime>0L) DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(lastSyncTime)) else "")
        })
    }
}
