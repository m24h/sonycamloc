package top.m24h.sonycamloc

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.databinding.DataBindingUtil
import androidx.databinding.Observable
import androidx.databinding.Observable.OnPropertyChangedCallback
import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import androidx.databinding.ObservableInt
import top.m24h.sonycamloc.databinding.ActivityMainBinding

private const val CAMERA_SLOTS = 3

class MainActivity:AppActivity<ActivityMainBinding>(R.layout.activity_main) {
    val versionName :String by lazy {
        packageManager.getPackageInfo(packageName, 0).versionName?:""
    }
    // cameras
    class CameraInfo {
        // maintained by service
        val ready = ObservableBoolean(false)
        val remoteFeatures = ObservableField<String>()
        // maintained by this
        val mac = ObservableField<String>()
        val name = ObservableField<String>()
        val type = ObservableField<String>()
        val clazz = ObservableField<String>()
    }
    val cameras = List(3) { CameraInfo() }
    // other data maintained by service
    val longitude = ObservableField<String>()
    val latitude = ObservableField<String>()
    val altitude = ObservableField<String>()
    val lastSyncTime = ObservableField<String>()
    // other data maintained by this
    val locEnable = ObservableBoolean(false)
    var faithMode = ObservableInt(1)

    // maintain settings
    private fun loadSettings() {
        with(getSharedPreferences("setting", MODE_PRIVATE)) {
            for (i in 0..CAMERA_SLOTS-1) {
                cameras[i].name.set(runCatching { getString("cameraName$i", null) }.getOrNull())
                cameras[i].type.set(runCatching { getString("cameraType$i", null) }.getOrNull())
                cameras[i].clazz.set(runCatching { getString("cameraClass$i", null) }.getOrNull())
                cameras[i].mac.set(runCatching { getString("cameraMac$i", null) }.getOrNull())
            }
            locEnable.set(runCatching { getBoolean("locEnable", false) }.getOrNull() == true)
            faithMode.set(runCatching { getInt("faithMode", 1) }.getOrNull()?:1)
        }
    }
    private fun saveSettings() {
        getSharedPreferences("setting", MODE_PRIVATE).edit {
            for (i in 0..CAMERA_SLOTS-1) {
                putString("cameraName$i", cameras[i].name.get())
                putString("cameraType$i", cameras[i].type.get())
                putString("cameraClass$i", cameras[i].clazz.get())
                putString("cameraMac$i", cameras[i].mac.get())
            }
            putBoolean("locEnable", locEnable.get())
            putInt("faithMode", faithMode.get())
        }
    }

    // start or update services
    private fun commandService(type:String) {
        startForegroundService(Intent(this, MainService::class.java).apply {
            putExtra("type", type)
            for (i in 0..CAMERA_SLOTS-1) {
                putExtra("cameraClass$i", cameras[i].clazz.get())
                putExtra("cameraMac$i", cameras[i].mac.get())
            }
            putExtra("locEnable", locEnable.get())
            putExtra("faithMode", faithMode.get())
        })
    }

    // message from others, should be register/unregister on create/destroy
    private val broadcastFilter=IntentFilter(MainService.broadcastAction)
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MainService.broadcastAction) {
                for (i in 0..CAMERA_SLOTS-1) {
                    if (intent.hasExtra("ready$i"))
                        cameras[i].ready.set(intent.getBooleanExtra("ready$i", false))
                    if (intent.hasExtra("remoteFeatures$i"))
                        cameras[i].remoteFeatures.set(intent.getStringExtra("remoteFeatures$i") ?: "")
                }
                if (intent.hasExtra("longitude"))
                    longitude.set(intent.getStringExtra("longitude")?:"")
                if (intent.hasExtra("latitude"))
                    latitude.set(intent.getStringExtra("latitude")?:"")
                if (intent.hasExtra("altitude"))
                    altitude.set(intent.getStringExtra("altitude")?:"")
                if (intent.hasExtra("lastSyncTime"))
                    lastSyncTime.set(intent.getStringExtra("lastSyncTime")?:"")
            }
        }
    }

    // runtime permissions
    private fun checkPermissionsAndStartService() {
        val need=arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS)
            .filter { ContextCompat.checkSelfPermission(this, it)!=PackageManager.PERMISSION_GRANTED }
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
            val remain = need.filter { it !in map || map[it]!=true }
            if (remain.isNotEmpty()) {
                Log.e("MainActivity.checkPermissionsAndStartService",
                    "missing permissions: " + remain.joinToString(","))
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.permission_need)
                    .setMessage(R.string.permission_need_msg)
                    .setOnDismissListener { _ -> finish() }
                    .create()
                    .apply { setCanceledOnTouchOutside(true) }
                    .show()
            } else {
                commandService("start")
            }
        }.launch(need.filter{!ActivityCompat.shouldShowRequestPermissionRationale(this@MainActivity, it)}.toTypedArray())
    }

    // on create / destroy
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.model = this
        // load settings
        loadSettings()
        // start service
        checkPermissionsAndStartService()
        // hooks for fields, cameraName/cameraType is excluded coz it's with cameraMAC
        val propertyChangedCallback = object : OnPropertyChangedCallback() {
            override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                saveSettings()
                commandService("update")
            }
        }
        for (i in 0..CAMERA_SLOTS-1) {
            cameras[i].mac.addOnPropertyChangedCallback(propertyChangedCallback)
        }
        locEnable.addOnPropertyChangedCallback(propertyChangedCallback)
        faithMode.addOnPropertyChangedCallback(propertyChangedCallback)
        // camera down-up (non-click) buttons
        setDownUpListener(binding.btnZoomW, ::onZoomW)
        setDownUpListener(binding.btnZoomT, ::onZoomT)
        setDownUpListener(binding.btnFocus, ::onFocus)
        setDownUpListener(binding.btnShot,  ::onShot)
        // receive message from service
        registerReceiver(broadcastReceiver, broadcastFilter, RECEIVER_NOT_EXPORTED)
        // may request bluetooth enabled
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager?)
            ?.adapter?.takeIf{!it.isEnabled}?.let{
                activityResultRegistry.register(
                    System.currentTimeMillis().toString(),
                    ActivityResultContracts.StartActivityForResult()
                ){}.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
    }
    override fun onDestroy() {
        unregisterReceiver(broadcastReceiver)
        super.onDestroy()
    }

    // button functions
    fun onExit() {
        commandService("stop")
        finish()
    }
    fun onScan(slot: Int) {
        getResources().getColor(R.color.primary, null)
        activityResultRegistry.register(
            System.currentTimeMillis().toString(),
            ActivityResultContracts.StartActivityForResult()
        ) {
            it ?.takeIf { it.resultCode == RESULT_OK }
                ?.data ?.let {
                    val mac=it.getStringExtra("mac")
                    // check if it already exists in other slot
                    for (i in 0..CAMERA_SLOTS-1) {
                        if (i!=slot && mac!=null && mac.uppercase()==cameras[i].mac.get()?.uppercase()) {
                            Toast.makeText(this, R.string.scan_exist, Toast.LENGTH_LONG).show()
                            return@let
                        }
                    }
                    cameras[slot].type.set(it.getStringExtra("type"))
                    cameras[slot].name.set(it.getStringExtra("name"))
                    cameras[slot].clazz.set(it.getStringExtra("class"))
                    // must be set after name/type, this will cause updating/save, which will use name/type/class
                    cameras[slot].mac.set(mac)
                }
        }.launch(Intent(this, ScanActivity::class.java))
    }
    private fun sendRemote(remote:String, active:Boolean) {
        startForegroundService(Intent(this, MainService::class.java).apply {
            putExtra("type", "remote")
            putExtra("slot", 0) // only the first camera is remotely controlled
            putExtra("remote", remote)
            putExtra("active", active)
        })
    }
    fun onZoomW(down:Boolean) {
        sendRemote("Wide", down)
    }
    fun onZoomT(down:Boolean) {
        sendRemote("Tele", down)
    }
    fun onFocus(down:Boolean) {
        sendRemote("Focus", down)
    }
    fun onShot(down:Boolean) {
        sendRemote("Shot", down)
    }
}
