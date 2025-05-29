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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.databinding.DataBindingUtil
import androidx.databinding.Observable
import androidx.databinding.Observable.OnPropertyChangedCallback
import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import top.m24h.sonycamloc.databinding.ActivityMainBinding

class MainActivity:AppActivity<ActivityMainBinding>(R.layout.activity_main) {
    lateinit var versionName :String
    // data maintained by others
    val connected = ObservableBoolean(false)
    val canRemote = ObservableBoolean(false)
    val longitude = ObservableField<String>()
    val latitude = ObservableField<String>()
    val lastSyncTime = ObservableField<String>()
    // data maintained by me
    val cameraMAC = ObservableField<String>()
    val cameraName = ObservableField<String>()
    val locEnable = ObservableBoolean(false)
    val faithMode = ObservableBoolean(false)
    // maintain settings
    private fun loadSettings() {
        with(getSharedPreferences("setting", MODE_PRIVATE)) {
            cameraMAC.set(getString("cameraMAC", null))
            cameraName.set(getString("cameraName", null))
            locEnable.set(getBoolean("locEnable", false))
            faithMode.set(getBoolean("faithMode", false))
        }
    }
    private fun saveSettings() {
        getSharedPreferences("setting", MODE_PRIVATE).edit {
            putString("cameraMAC", cameraMAC.get())
            putString("cameraName", cameraName.get())
            putBoolean("locEnable", locEnable.get())
            putBoolean("faithMode", faithMode.get())
        }
    }
    private fun commandService(type:String) {
        startForegroundService(Intent(this, MainService::class.java).apply {
            putExtra("type", type)
            putExtra("cameraMAC", cameraMAC.get())
            putExtra("locEnable", locEnable.get())
            putExtra("faithMode", faithMode.get())
        })
    }
    // message from others, should be register/unregister on create/destroy
    private val broadcastFilter=IntentFilter(MainService.broadcastAction)
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MainService.broadcastAction) {
                if (intent.hasExtra("connected"))
                    connected.set(intent.getBooleanExtra("connected", false))
                if (intent.hasExtra("canRemote"))
                    canRemote.set(intent.getBooleanExtra("canRemote", false))
                if (intent.hasExtra("longitude"))
                    longitude.set(intent.getStringExtra("longitude"))
                if (intent.hasExtra("latitude"))
                    latitude.set(intent.getStringExtra("latitude"))
                if (intent.hasExtra("lastSyncTime"))
                    lastSyncTime.set(intent.getStringExtra("lastSyncTime"))
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
            val remain = need.filter { it !in map || !map[it]!! }
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
        // get version name from package info
        versionName= packageManager.getPackageInfo(packageName, 0).versionName?:""
        // load settings
        loadSettings()
        // start service
        checkPermissionsAndStartService()
        // hooks for fields, cameraName is excluded coz it's for showing only
        val propertyChangedCallback = object : OnPropertyChangedCallback() {
            override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
                saveSettings()
                commandService("update")
            }
        }
        cameraMAC.addOnPropertyChangedCallback(propertyChangedCallback)
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
                ) { _-> }.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        //
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
    fun onScan() {
        activityResultRegistry.register(
            System.currentTimeMillis().toString(),
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result != null && result.resultCode == RESULT_OK && result.data != null) {
                // set cameraName first, it's not observed as a trigger
                cameraName.set(result.data!!.getStringExtra("name"))
                cameraMAC.set(result.data!!.getStringExtra("mac"))
            }
        }.launch(Intent(this, ScanActivity::class.java))
    }
    private fun sendRemote(remote:ByteArray) {
        startForegroundService(Intent(this, MainService::class.java).apply {
            putExtra("type", "remote")
            putExtra("remote", remote)
        })
    }
    fun onZoomW(down:Boolean) {
        sendRemote(if (down) SonyCam.REMOTE_WIDE_DOWN else SonyCam.REMOTE_WIDE_UP)
    }
    fun onZoomT(down:Boolean) {
        sendRemote(if (down) SonyCam.REMOTE_TELE_DOWN else SonyCam.REMOTE_TELE_UP)
    }
    fun onFocus(down:Boolean) {
        sendRemote(if (down) SonyCam.REMOTE_FOCUS_DOWN else SonyCam.REMOTE_FOCUS_UP)
    }
    fun onShot(down:Boolean) {
        sendRemote(if (down) SonyCam.REMOTE_SHOT_DOWN else SonyCam.REMOTE_SHOT_UP)
    }
}
