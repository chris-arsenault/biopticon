package io.ahara.biopticon.probe

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import java.util.UUID

data class NearbyDevice(val id: String, val label: String, val device: BluetoothDevice)

/** Only the owner-selected peripheral is connected. No pairing, writes or subscriptions. */
@SuppressLint("MissingPermission")
class BluetoothProbes(private val context: Context, private val log: EventLog) {
    suspend fun scan(): List<NearbyDevice> {
        val run = log.begin()
        val found = linkedMapOf<String, NearbyDevice>()
        val manager = context.getSystemService(BluetoothManager::class.java)
        val scanner = manager.adapter?.bluetoothLeScanner
        if (scanner == null) {
            log.emit(run, "HM-09", "scan", "ble", "advertisement_discovery", "UNSUPPORTED", "BLUETOOTH_UNAVAILABLE")
            log.finish(run, "FINISHED"); return emptyList()
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                synchronized(found) {
                    if (found.size >= 40 || found.containsKey(result.device.address)) return
                    val id = UUID.randomUUID().toString()
                    found[result.device.address] = NearbyDevice(id, result.scanRecord?.deviceName ?: "Unnamed peripheral ${found.size + 1}", result.device)
                    val record = result.scanRecord
                    val findings = mutableListOf(Finding("metadata", "peripheral_alias", text = id), Finding("capability", "advertised_name_present", flag = record?.deviceName != null))
                    record?.serviceUuids?.forEach { findings.add(Finding("protocol", "service_uuid", text = it.toString())) }
                    record?.manufacturerSpecificData?.let { data ->
                        for (i in 0 until data.size()) {
                            findings.add(Finding("protocol", "manufacturer_type", count = data.keyAt(i).toLong()))
                            findings.add(Finding("counter", "manufacturer_data_bytes", count = data.valueAt(i).size.toLong()))
                        }
                    }
                    log.emit(run, "HM-09", id, "ble", "advertisement_discovery", "PASS", "ADVERTISEMENT_OBSERVED", findings)
                }
            }
            override fun onScanFailed(code: Int) {
                log.emit(run, "HM-09", "scan", "ble", "advertisement_discovery", "FAIL", "SCAN_FAILED", listOf(Finding("error", "scan_code", count = code.toLong())))
            }
        }
        try { scanner.startScan(callback); delay(12000) }
        finally { scanner.stopScan(callback); log.finish(run, "FINISHED") }
        return synchronized(found) { found.values.toList() }
    }

    suspend fun enumerate(selected: NearbyDevice) {
        val run = log.begin()
        var connection: BluetoothGatt? = null
        try {
            withTimeout(15000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val callback = object : BluetoothGattCallback() {
                        private fun done() { if (continuation.isActive) continuation.resume(Unit) }
                        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, state: Int) {
                            if (!continuation.isActive) return
                            if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                                log.emit(run, "HM-10", selected.id, "ble", "gatt_connect", "INCONCLUSIVE", "CONNECTION_ENDED", listOf(Finding("error", "gatt_status", count = status.toLong())))
                                done()
                            } else if (state == BluetoothProfile.STATE_CONNECTED && !gatt.discoverServices()) done()
                        }
                        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                            if (!continuation.isActive) return
                            log.emit(run, "HM-10", selected.id, "ble", "discover_services", if (status == 0) "PASS" else "FAIL", "GATT_STATUS", listOf(Finding("error", "gatt_status", count = status.toLong())))
                            if (status == 0) gatt.services.forEach { service ->
                                service.characteristics.forEach { characteristic ->
                                    log.emit(run, "HM-10", characteristic.uuid.toString(), "ble", "characteristic_inventory", "PASS", "DISCOVERED", listOf(
                                        Finding("protocol", "service_uuid", text = service.uuid.toString()), Finding("protocol", "characteristic_uuid", text = characteristic.uuid.toString()),
                                        Finding("capability", "properties", count = characteristic.properties.toLong()), Finding("metadata", "peripheral_alias", text = selected.id)))
                                }
                            }
                            done()
                        }
                    }
                    connection = selected.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                    continuation.invokeOnCancellation { connection?.disconnect() }
                }
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            log.emit(run, "HM-10", selected.id, "ble", "discover_services", "INCONCLUSIVE", "GATT_TIMEOUT")
        } finally { connection?.disconnect(); connection?.close(); log.finish(run, "FINISHED") }
    }
}
