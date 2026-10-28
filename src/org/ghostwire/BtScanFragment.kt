/*
 * Copyright 2026 Varakumar.
 *
 * This file is part of GhostWire.
 *
 * GhostWire is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * GhostWire is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with GhostWire.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.ghostwire

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * Passive Bluetooth recon with the phone's own radio: a BLE advertiser scan
 * (extended/legacy, all PHYs) plus a BR/EDR inquiry, running together. This is the
 * real capability the internal WCN6855 exposes — it receives advertising from nearby
 * devices it is not paired to (verified live, see
 * .claude/research/bluetooth-monitor-capability.md). It cannot follow other devices'
 * active connections; that needs an external Ubertooth/nRF52840.
 *
 * Each device is shown with everything the air report carries: address, name, RSSI,
 * type, connectable/legacy, PHYs, TX power, advertising SID, periodic interval,
 * service UUIDs, service data and manufacturer data. Tap a row to expand full detail.
 */
class BtScanFragment : Fragment() {

    private lateinit var scanBtn: Button
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var modeSpinner: Spinner

    // Label -> ScanSettings scan mode. Low power and opportunistic are the modes the
    // user asked for: longer-interval duty cycles and piggy-backing on other apps'
    // scans surface slow or stealthy advertisers that a constant low-latency sweep,
    // or the controller's own adv filtering, can drop.
    private val scanModes = listOf(
        "Low latency (fast, catches most)" to ScanSettings.SCAN_MODE_LOW_LATENCY,
        "Balanced" to ScanSettings.SCAN_MODE_BALANCED,
        "Low power (slow, more thorough)" to ScanSettings.SCAN_MODE_LOW_POWER,
        "Opportunistic (passive, piggy-back)" to ScanSettings.SCAN_MODE_OPPORTUNISTIC,
    )

    private val adapter: BluetoothAdapter? by lazy {
        requireContext().getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
    }

    private var scanning = false
    private val seen = LinkedHashMap<String, Dev>()
    private val expanded = HashSet<String>()

    /** One nearby device, merged from every report/inquiry hit on the same address. */
    private class Dev(val address: String) {
        var name: String? = null
        var rssi: Int = 0
        var type: Int = BluetoothDevice.DEVICE_TYPE_UNKNOWN
        var bond: Int = BluetoothDevice.BOND_NONE
        var connectable: Boolean? = null
        var legacy: Boolean? = null
        var primaryPhy = 0
        var secondaryPhy = 0
        var sid = ScanResult.SID_NOT_PRESENT
        var txPower: Int? = null
        var periodicMs = 0
        var uuids: List<String> = emptyList()
        var mfg: String? = null
        var svcData: String? = null
        var btClass: Int? = null
        var source = ""       // "LE", "classic", or "LE+classic"
        var lastSeen = 0L
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_btscan, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        scanBtn = view.findViewById(R.id.scan_toggle)
        status = view.findViewById(R.id.scan_status)
        list = view.findViewById(R.id.scan_list)
        modeSpinner = view.findViewById(R.id.scan_mode)
        modeSpinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item,
            scanModes.map { it.first }
        )
        scanBtn.setOnClickListener { if (scanning) stop() else start() }
    }

    // BLUETOOTH_SCAN/CONNECT are runtime perms; self-grant as root like the rest of
    // the app, so the scan works without a manual grant trip through Settings.
    private fun grantPerms() {
        Root.run(
            "pm grant org.ghostwire android.permission.BLUETOOTH_SCAN 2>&1; " +
                "pm grant org.ghostwire android.permission.BLUETOOTH_CONNECT 2>&1; " +
                "pm grant org.ghostwire android.permission.ACCESS_FINE_LOCATION 2>&1"
        )
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            status.text = "Turn Bluetooth on first."
            return
        }
        grantPerms()
        seen.clear(); expanded.clear(); list.removeAllViews()
        scanning = true
        scanBtn.text = "Stop"
        val mode = scanModes[modeSpinner.selectedItemPosition]
        status.text = "Scanning (${mode.first.substringBefore(" (")}) — BLE advertisers + classic inquiry…"

        // All matches, non-legacy so extended advertising is reported too, on every PHY.
        // Aggressive match + max advertisements report even weak/one-shot advertisers
        // instead of only strong, repeating ones.
        val settings = ScanSettings.Builder()
            .setScanMode(mode.second)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .setLegacy(false)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .build()
        try {
            a.bluetoothLeScanner?.startScan(null, settings, leCb)
        } catch (e: Exception) {
            status.text = "BLE scan failed: ${e.message}"
        }

        // Classic inquiry.
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // Android 14 requires an export flag; these are protected system broadcasts.
        ContextCompat.registerReceiver(requireContext(), classicRx, f, ContextCompat.RECEIVER_EXPORTED)
        try {
            if (a.isDiscovering) a.cancelDiscovery()
            a.startDiscovery()
        } catch (e: Exception) { /* BLE still runs */ }
    }

    @SuppressLint("MissingPermission")
    private fun stop() {
        scanning = false
        scanBtn.text = "Scan"
        try { adapter?.bluetoothLeScanner?.stopScan(leCb) } catch (_: Exception) {}
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        runCatching { requireContext().unregisterReceiver(classicRx) }
        status.text = "Stopped — ${seen.size} device(s) seen."
    }

    private val leCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = seen.getOrPut(result.device.address) { Dev(result.device.address) }
            d.rssi = result.rssi
            d.lastSeen = System.currentTimeMillis()
            d.type = result.device.type
            d.bond = result.device.bondState
            d.connectable = result.isConnectable
            d.legacy = result.isLegacy
            d.primaryPhy = result.primaryPhy
            d.secondaryPhy = result.secondaryPhy
            d.sid = result.advertisingSid
            d.txPower = result.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT }
            d.periodicMs = result.periodicAdvertisingInterval
            d.source = if (d.source.contains("classic")) "LE+classic" else "LE"
            val rec = result.scanRecord
            if (rec != null) {
                rec.deviceName?.let { d.name = it }
                d.uuids = rec.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
                rec.manufacturerSpecificData?.let { msd ->
                    d.mfg = (0 until msd.size()).joinToString("; ") { i ->
                        "0x%04X=%s".format(msd.keyAt(i), msd.valueAt(i).toHex())
                    }.ifBlank { null }
                }
                rec.serviceData?.let { sd ->
                    d.svcData = sd.entries.joinToString("; ") { (u, v) ->
                        "${u.uuid}=${v.toHex()}"
                    }.ifBlank { null }
                }
            }
            if (d.name == null) runCatching { result.device.name?.let { d.name = it } }
            render()
        }

        override fun onScanFailed(errorCode: Int) {
            status.post { status.text = "BLE scan error $errorCode" }
        }
    }

    private val classicRx = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev: BluetoothDevice =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val d = seen.getOrPut(dev.address) { Dev(dev.address) }
                    d.rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        .takeIf { it != Short.MIN_VALUE.toInt() } ?: d.rssi
                    runCatching { dev.name?.let { d.name = it } }
                    d.type = dev.type
                    d.bond = dev.bondState
                    d.lastSeen = System.currentTimeMillis()
                    val cls = intent.getParcelableExtra<BluetoothClass>(BluetoothDevice.EXTRA_CLASS)
                    d.btClass = cls?.deviceClass
                    d.source = if (d.source.contains("LE")) "LE+classic" else "classic"
                    render()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    // Classic inquiry is one-shot (~12 s); relaunch while scanning is on.
                    if (scanning) runCatching { adapter?.startDiscovery() }
                }
            }
        }
    }

    private fun render() {
        val sorted = seen.values.sortedByDescending { it.rssi }
        list.post {
            if (!isAdded) return@post
            list.removeAllViews()
            status.text = "${if (scanning) "Scanning" else "Stopped"} — ${sorted.size} device(s)"
            for (d in sorted) list.addView(row(d))
        }
    }

    private fun row(d: Dev): View {
        val tv = TextView(requireContext()).apply {
            setPadding(0, 12, 0, 12)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setTextIsSelectable(false)
            text = if (expanded.contains(d.address)) detail(d) else summary(d)
            setOnClickListener {
                if (!expanded.add(d.address)) expanded.remove(d.address)
                text = if (expanded.contains(d.address)) detail(d) else summary(d)
            }
        }
        return tv
    }

    private fun summary(d: Dev): String {
        val nm = d.name ?: "(no name)"
        return "${d.rssi} dBm  ${d.address}  [${d.source}]  $nm  ▸"
    }

    private fun detail(d: Dev): String = buildString {
        appendLine("${d.address}   ${d.rssi} dBm   [${d.source}]  ▾")
        appendLine("  name        : ${d.name ?: "—"}")
        appendLine("  type        : ${typeName(d.type)}   bond: ${bondName(d.bond)}")
        d.connectable?.let { appendLine("  connectable : $it") }
        d.legacy?.let { appendLine("  adv         : ${if (it) "legacy" else "extended"}") }
        if (d.primaryPhy != 0 || d.secondaryPhy != 0)
            appendLine("  PHY         : primary ${phy(d.primaryPhy)}, secondary ${phy(d.secondaryPhy)}")
        if (d.sid != ScanResult.SID_NOT_PRESENT) appendLine("  adv SID     : ${d.sid}")
        d.txPower?.let { appendLine("  tx power    : $it dBm") }
        if (d.periodicMs != 0) appendLine("  periodic    : ${d.periodicMs * 125 / 100} ms")
        d.btClass?.let { appendLine("  class       : 0x%06X (%s)".format(it, classMajor(it))) }
        if (d.uuids.isNotEmpty()) {
            appendLine("  services    :")
            d.uuids.forEach { appendLine("      $it") }
        }
        d.svcData?.let { appendLine("  svc data    : $it") }
        d.mfg?.let { appendLine("  mfg data    : $it") }
    }

    private fun typeName(t: Int) = when (t) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic (BR/EDR)"
        BluetoothDevice.DEVICE_TYPE_LE -> "LE"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual (BR/EDR + LE)"
        else -> "unknown"
    }

    private fun bondName(b: Int) = when (b) {
        BluetoothDevice.BOND_BONDED -> "bonded"
        BluetoothDevice.BOND_BONDING -> "bonding"
        else -> "none"
    }

    private fun phy(p: Int) = when (p) {
        BluetoothDevice.PHY_LE_1M -> "1M"
        BluetoothDevice.PHY_LE_2M -> "2M"
        BluetoothDevice.PHY_LE_CODED -> "Coded"
        ScanResult.PHY_UNUSED -> "—"
        else -> "?"
    }

    private fun classMajor(c: Int) = when (c and 0x1f00) {
        BluetoothClass.Device.Major.PHONE -> "Phone"
        BluetoothClass.Device.Major.COMPUTER -> "Computer"
        BluetoothClass.Device.Major.AUDIO_VIDEO -> "Audio/Video"
        BluetoothClass.Device.Major.WEARABLE -> "Wearable"
        BluetoothClass.Device.Major.HEALTH -> "Health"
        BluetoothClass.Device.Major.PERIPHERAL -> "Peripheral"
        BluetoothClass.Device.Major.IMAGING -> "Imaging"
        BluetoothClass.Device.Major.TOY -> "Toy"
        BluetoothClass.Device.Major.NETWORKING -> "Networking"
        else -> "other"
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    override fun onPause() {
        super.onPause()
        if (scanning) stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (scanning) stop()
    }
}
