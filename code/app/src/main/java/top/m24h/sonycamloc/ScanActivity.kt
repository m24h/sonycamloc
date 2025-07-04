package top.m24h.sonycamloc

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import top.m24h.android.DataBindListViewAdapter
import top.m24h.sonycamloc.databinding.ActivityScanBinding
import top.m24h.sonycamloc.databinding.ScanListItemBinding
import java.util.SortedSet

class ScanActivity : AppActivity<ActivityScanBinding>(R.layout.activity_scan)
                     , AdapterView.OnItemClickListener {
    // listview of scan result
    lateinit var listViewAdapter:DataBindListViewAdapter<ScanResult, ScanListItemBinding, SortedSet<ScanResult>>
    // scanner
    var scanner :BluetoothLeScanner? = null
    // on create / destroy
    @RequiresPermission(allOf=[Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT])
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.model = this
        // listview of scan result
        listViewAdapter=DataBindListViewAdapter(this, R.layout.scan_list_item, "setItem",
            sortedSetOf<ScanResult>( object : Comparator<ScanResult> {
                @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                override fun compare(p0: ScanResult?, p1: ScanResult?): Int {
                    return ((if (Camera.type(p0?.scanRecord?.manufacturerSpecificData)!=null) "A" else "B")+(p0?.device?.name?:""))
                        .compareTo((if (Camera.type(p1?.scanRecord?.manufacturerSpecificData)!=null) "A" else "B")+(p1?.device?.name?:""))
                }
            })
        )
        binding.deviceList.adapter = listViewAdapter
        binding.deviceList.onItemClickListener = this
        // just do scan
        scanner=(getSystemService(BLUETOOTH_SERVICE) as BluetoothManager?)
            ?.adapter?.takeIf{it.isEnabled}?.bluetoothLeScanner
        scanner?.startScan(scanCallback)
            ?:Toast.makeText(this, R.string.ble_need_enable, Toast.LENGTH_LONG).show()
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun onDestroy() {
        scanner?.stopScan(scanCallback)
        super.onDestroy()
    }
    // button onClick
    fun onBlank() {
        setResult(RESULT_OK, Intent()
            .putExtra("mac", null as String?)
            .putExtra("name", null as String?)
            .putExtra("type", null as String?)
            .putExtra("class", null as String?)
        )
        finish()
    }
    fun onBack() {
        setResult(RESULT_CANCELED)
        finish()
    }
    // process scanned devices
    val scanCallback = object: ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            if (result?.device?.name?.isNotEmpty()==true && result.device?.address?.isNotEmpty()==true) {
                runOnUiThread {
                    val count=listViewAdapter.data.count()
                    listViewAdapter.data.add(result)
                    if (count!=listViewAdapter.data.count())
                        listViewAdapter.notifyDataSetChanged()
                }
            }
        }
    }
    // get a device and return
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onItemClick(parent: AdapterView<*>?, view: View?, pos: Int, id:Long) {
        // debouncing
        if (!binding.deviceList.isEnabled)  return
        binding.deviceList.isEnabled = false
        val scanResult=listViewAdapter.data.elementAt(pos)
        val type=Camera.type(scanResult.scanRecord?.manufacturerSpecificData)
        if (type==null) {
            Toast.makeText(this, R.string.ble_unknown_device, Toast.LENGTH_SHORT).show()
            binding.deviceList.isEnabled = true
        } else if (type.needBind && scanResult.device.bondState!=BluetoothDevice.BOND_BONDED) {
            Toast.makeText(this, R.string.ble_need_pair, Toast.LENGTH_SHORT).show()
            binding.deviceList.isEnabled = true
        } else {
            setResult(RESULT_OK, Intent()
                .putExtra("mac", scanResult.device.address)
                .putExtra("name", scanResult.device.name)
                .putExtra("type", type.name)
                .putExtra("class", type.cameraClass().name)
            )
            finish()
        }
    }
}