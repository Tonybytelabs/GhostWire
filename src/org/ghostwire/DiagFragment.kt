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
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment

/**
 * DIAG tab: inspect and drive the Qualcomm DIAG surface (/dev/diag) via root.
 * Raw capture hands a stream to QCSuper/SnoopSnitch; NV read/write and AT use
 * the bundled ghostwire_diag helper (/system_ext/bin) and a modem AT port.
 * See .claude/research/nethunter-radio-pentest.md section 2.
 */
class DiagFragment : Fragment() {

    private lateinit var out: TextView
    private lateinit var item: EditText
    private lateinit var hex: EditText
    private lateinit var at: EditText
    private val helper = "/system_ext/bin/ghostwire_diag"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_diag, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        out = view.findViewById(R.id.diag_out)
        item = view.findViewById(R.id.diag_nv_item)
        hex = view.findViewById(R.id.diag_nv_hex)
        at = view.findViewById(R.id.diag_at_cmd)
        action(view, R.id.diag_status) { diagStatus() }
        action(view, R.id.diag_capture) { rawCapture() }
        action(view, R.id.diag_nv_read) { nvRead() }
        action(view, R.id.diag_at_send) { atSend() }
        view.findViewById<Button>(R.id.diag_nv_write).setOnClickListener { confirmNvWrite() }
        view.findViewById<Button>(R.id.diag_copy).copyOnClick(out)
    }

    private fun action(view: View, id: Int, block: () -> String) {
        val b = view.findViewById<Button>(id)
        b.setOnClickListener { run(b.text.toString(), block) }
    }

    private fun run(label: String, block: () -> String) {
        out.text = "$label …"
        Thread {
            val result = try { block() } catch (e: Exception) { "error: ${e.message}" }
            out.post { out.text = result }
        }.start()
    }

    private fun diagStatus(): String {
        val cmd = "ls -laZ /dev/diag 2>&1; echo '--- daemons ---'; " +
            "ps -A 2>/dev/null | grep -iE 'diag-router|devicediagnostics|diag_mdlog' | grep -v grep; " +
            "echo '--- helper ---'; ls -l $helper 2>&1"
        return Root.run(cmd).out
    }

    private fun rawCapture(): String {
        val cmd = "d=/sdcard/ghostwire; mkdir -p \$d; f=\$d/diag-\$(date +%s).bin; " +
            "timeout 5 cat /dev/diag > \$f 2>/dev/null; echo \"saved \$f\"; ls -l \$f 2>&1"
        return Root.run(cmd).out +
            "\n\nRaw DIAG stream, not decoded. Parse with QCSuper (qcsuper --dlf-read <file>)."
    }

    private fun nvItem(): Int? = item.text.toString().trim().toIntOrNull()

    private fun nvRead(): String {
        val i = nvItem() ?: return "Enter an NV item number first."
        return Root.run("$helper nvread $i").out.ifBlank { "No output — check the helper is installed." }
    }

    /** NV write can brick the modem's NV, so confirm, then read back after. */
    private fun confirmNvWrite() {
        val i = nvItem() ?: run { out.text = "Enter an NV item number first."; return }
        val data = hex.text.toString().trim()
        if (data.isEmpty() || data.length % 2 != 0) {
            out.text = "Enter the new value as an even-length hex string (up to 128 bytes)."
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Write NV item $i?")
            .setMessage("This writes directly to the modem's NV. A wrong value can break the " +
                "radio/IMEI. Only on your own device, and note the current value first (Read).")
            .setPositiveButton("Write") { _, _ ->
                run("NV write") {
                    val before = Root.run("$helper nvread $i").out.trim()
                    val w = Root.run("$helper nvwrite $i $data").out.trim()
                    val after = Root.run("$helper nvread $i").out.trim()
                    "before: $before\nwrite : $w\nafter : $after"
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** AT passthrough needs a modem AT serial port; probe the usual nodes. */
    private fun atSend(): String {
        val c = at.text.toString().trim().ifEmpty { "ATI" }
        val cmd = "for n in /dev/ttyGS0 /dev/smd7 /dev/smd8 /dev/smd11 /dev/at_usb0 /dev/at_mdm0; do " +
            "[ -c \$n ] && [ -w \$n ] && { printf '%s\\r' '$c' > \$n; timeout 2 cat \$n; echo \"[via \$n]\"; exit 0; }; " +
            "done; echo 'no writable AT port found'"
        val r = Root.run(cmd).out
        return if (r.contains("no writable AT port")) {
            "No modem AT port is exposed in this USB/modem composition, so AT passthrough isn't " +
                "available here. NV read/write (above) is the reachable DIAG path. To get an AT " +
                "port you'd switch the USB composition to one that exports diag,serial (modem AT)."
        } else r
    }
}
