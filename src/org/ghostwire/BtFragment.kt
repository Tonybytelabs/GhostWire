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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Bluetooth HCI snoop. The internal WCN6855 controller has no promiscuous/monitor
 * mode for other devices' traffic (signed firmware, confirmed by decompiling the
 * HAL -- see .claude/research/nethunter-radio-pentest.md section 1). What it can do
 * is log this phone's OWN HCI/ACL to btsnoop_hci.log, which this screen turns on and
 * pulls for Wireshark. Off-device sniffing needs an external Ubertooth/nRF52840.
 */
class BtFragment : Fragment() {

    private lateinit var out: TextView
    private val log = "/data/misc/bluetooth/logs/btsnoop_hci.log"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_bt, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        out = view.findViewById(R.id.bt_out)
        act(view, R.id.bt_enable) { enable() }
        act(view, R.id.bt_disable) { disable() }
        act(view, R.id.bt_status) { status() }
        act(view, R.id.bt_pull) { pull() }
    }

    private fun act(v: View, id: Int, block: () -> String) {
        val b = v.findViewById<Button>(id)
        b.setOnClickListener {
            out.text = "${b.text} …"
            Thread {
                val r = try { block() } catch (e: Exception) { "error: ${e.message}" }
                out.post { out.text = r }
            }.start()
        }
    }

    // Snoop is read at stack start, so the adapter is cycled to apply the mode.
    private fun enable(): String {
        Root.run("setprop persist.bluetooth.btsnooplogmode full")
        Root.run("svc bluetooth disable")
        Thread.sleep(1500)
        Root.run("svc bluetooth enable")
        return "HCI snoop enabled (persist.bluetooth.btsnooplogmode=full) and Bluetooth cycled.\n" +
            "This phone's own HCI/ACL now logs to:\n$log\n\n" +
            "Pair/use a device, then Pull for Wireshark. Other devices' traffic is not captured — " +
            "the internal radio has no sniffer mode; use an external Ubertooth/nRF52840 for that."
    }

    private fun disable(): String {
        Root.run("setprop persist.bluetooth.btsnooplogmode disabled")
        Root.run("svc bluetooth disable"); Thread.sleep(1500); Root.run("svc bluetooth enable")
        return "HCI snoop disabled and Bluetooth cycled."
    }

    private fun status(): String =
        Root.run("echo mode=\$(getprop persist.bluetooth.btsnooplogmode); " +
            "ls -l $log 2>&1").out

    private fun pull(): String {
        val cmd = "d=/sdcard/ghostwire; mkdir -p \$d; o=\$d/btsnoop-\$(date +%s).log; " +
            "cp $log \$o 2>&1 && echo \"saved \$o\" && ls -l \$o"
        return Root.run(cmd).out + "\n\nOpen in Wireshark (btsnoop format)."
    }
}
