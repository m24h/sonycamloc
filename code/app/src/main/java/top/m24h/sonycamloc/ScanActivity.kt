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
                    return ((if (SonyCam.isSonyCam(p0?.scanRecord?.manufacturerSpecificData)) "A" else "B")+(p0?.device?.name?:""))
                        .compareTo((if (SonyCam.isSonyCam(p1?.scanRecord?.manufacturerSpecificData)) "A" else "B")+(p1?.device?.name?:""))
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
    fun onBack() {
        this.setResult(RESULT_CANCELED)
        finish()
    }
    // process scanned devices
    val scanCallback = object: ScanCallback() {
        var lastFlushTime=0L
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            val now=System.currentTimeMillis()
            if (result?.device?.name?.isNotEmpty()==true && result.device?.address?.isNotEmpty()==true) {
                listViewAdapter.data.add(result)
                if (now-lastFlushTime>999) {
                    lastFlushTime=now
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
        if (scanResult.device.bondState != BluetoothDevice.BOND_BONDED) {
            Toast.makeText(this, R.string.ble_need_pair, Toast.LENGTH_LONG).show()
            binding.deviceList.isEnabled = true
        } else {
            setResult(RESULT_OK, Intent()
                .putExtra("mac", scanResult.device.address)
                .putExtra("name", scanResult.device.name)
            )
            finish()
        }
    }
}