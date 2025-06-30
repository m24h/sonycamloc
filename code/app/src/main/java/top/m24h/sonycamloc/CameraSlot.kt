package top.m24h.sonycamloc

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Context.BLUETOOTH_SERVICE
import android.location.Location
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.m24h.android.BLE

private const val TIMEOUT_DEFAULT=5000L
private const val TIMEOUT_INIT=20000L
private const val TIMEOUT_FAITH=2500L
private const val RETRY_FAITH=2
private const val FAITH_MAX=3

private const val DELAY_FAILURE=10000L

// a proxy class for camera in slot
class CameraSlot (val context:Context, val asyncScope:CoroutineScope,
                  val onStatusChange:(CameraSlot)->Unit) {
    private var ble :BLE? =null
    private var camera :Camera? =null
    private var loopActor :SendChannel<Unit>? =null

    // configurations and command
    var faithMode = 1
    private var clazz:String? =null // java class name of camera
    private var mac:String? =null
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setMac(mac:String?, clazz:String?=null) {
        this.clazz=clazz
        if (mac!=this.mac) {
            reset()
            this.mac=mac
            activeLoop()
        }
    }
    var locEnable = false
    private var location: Location? =null
    fun setLocation(value:Location?, order:Int) {
        location = if (order>(FAITH_MAX-faithMode-1)) value else null
        if (location!=null && ready) activeLoop()
    }

    // states
    private var initialized=false
    private var discovered=false
    private var retryCount=0

    // status
    var lastSyncTime : Long? =null
    val isConnected
        get() = ble?.isConnected==true
    val ready
        get() = ble?.isConnected==true && camera!=null && initialized
    val remoteFeatures : String
        get() = camera?.remoteFeatures ?: ""

    // start async looper, this method can be called only once
    @OptIn(ObsoleteCoroutinesApi::class)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun start() {
        loopActor = loopActor ?: asyncScope.actor<(Unit)>(capacity=1)  {
            while (isActive) {
                try {
                    if (channel.receiveCatching().getOrNull()==null) break
                    loop()
                } catch (_:CancellationException) {
                    break
                } catch (e:Exception) {
                    Log.e("CameraSlot.startLoop", "Exception in loop()", e)
                    reset()
                    delay(DELAY_FAILURE)
                    activeLoop()
                }
            }
        }
    }
    // stop and close async looper, release resources, then this object can no longer be used
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun stop() {
        loopActor?.close() // maybe at most 1 loop still waiting
        loopActor=null
        reset()
    }
    // active looper once
    fun activeLoop()
        = loopActor?.trySend(Unit)

    // reset all state into initial state, it will not try to reconnect automatically
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun reset() {
        ble?.close()
        ble=null
        camera=null
        discovered=false
        initialized=false
        retryCount=0
        lastSyncTime = null
        onStatusChange(this) // high probability
    }

    // working loop
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun loop() {
        // try to auto-connect (maybe not really connect) if not connected
        if (ble==null && mac?.isNotEmpty()==true) {
            ble = runCatching {
                (context.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager?)
                    ?.adapter?.takeIf { it.isEnabled && it.state == BluetoothAdapter.STATE_ON }
                    ?.getRemoteDevice(mac)
            }.getOrNull() ?.let {
                BLE.open(context, it, true) {
                    if (it) runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                    onStatusChange(this@CameraSlot) // connection status is changed
                    activeLoop()
                } ?.apply {
                    timeout = TIMEOUT_DEFAULT
                }
            }
        }
        // if disconnected
        if (ble?.isConnected!=true) {
            initialized=false
            lastSyncTime=null
        }
        // initialize the connection just established, any error will cause re-connecting
        ble?.takeIf { it.isConnected && !initialized } ?.also { ble ->
            // MTU
            ble.requestMtu(
                    517, // 517 is recommended from Android 14
                    if (faithMode >= FAITH_MAX && retryCount++ < RETRY_FAITH) TIMEOUT_FAITH else TIMEOUT_INIT
                ) ?: return@also
            retryCount = 0
            // discover and get camera instance
            if (camera==null || !discovered || faithMode < FAITH_MAX) {
                if (ble.discoveryServices(TIMEOUT_INIT) != BluetoothGatt.GATT_SUCCESS) return@also
                camera = camera
                    ?: Camera.get(ble)
                    ?: clazz?.let { runCatching { Class.forName(it).getDeclaredConstructor().newInstance() as? Camera} } ?.getOrNull()
                if (camera?.discovery(ble)==false) return@also
                discovered = true
            }
            // config camera
            initialized = camera?.config(ble)!=false
            if (initialized) onStatusChange(this) // become ready
        } ?.takeIf { !initialized } ?.disconnect()
        // try to send geo-tag
        if (locEnable) {
            ble?.takeIf { it.isConnected }?.let { ble ->
                location?.let { loc -> camera ?.let { cam ->
                    val now = System.currentTimeMillis()
                    if (cam.geoTag(ble, loc, now)) {
                        lastSyncTime = now
                        onStatusChange(this)
                    } else ble.disconnect()
                }}}
        }
    }

    // send remote command, re-connect BLE if failed
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun remote(feature:String, active:Boolean)
        = ble ?.takeIf { it.isConnected } ?.let {
            if (camera?.remote(it, feature, active)==false) ble?.disconnect()
        }
}