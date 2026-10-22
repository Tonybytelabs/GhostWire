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
 * Raw NCI inspector. The ROM ships the NXP HAL with NFC_DEBUG_ENABLED=1 and
 * NXPLOG_NCIR/NCIX=0x04, so every NCI frame to/from the SN100 controller is
 * logged. This tab surfaces that stream and the nfcsnoop ring buffer without a
 * host PC. Tap a tag to see the live command/response bytes, or dump nfcsnoop
 * for offline decode. See .claude/research/nethunter-radio-pentest.md section 3.
 */
class NciFragment : Fragment() {

    private lateinit var out: TextView

    // NXP HAL + framework NFC log tags that carry raw NCI / HAL traffic.
    private val nciTags = "Nxp(Hal|NciR|NciX|Tml|Snoop)|libnfc_nci|NfcNci|BrcmNfcNfa|NfcService|NfcJni"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_nci, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        out = view.findViewById(R.id.nci_out)
        act(view, R.id.nci_live) { liveNci() }
        act(view, R.id.nci_snoop) { nfcsnoop() }
        view.findViewById<Button>(R.id.nci_copy).copyOnClick(out)
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

    /** Recent raw NCI frames from the HAL log. Tap an NFC tag to the phone first
     *  to generate traffic, then read it back here. */
    private fun liveNci(): String {
        val r = Root.run("logcat -d -t 4000 2>/dev/null | grep -aE '$nciTags' | tail -n 200")
        return if (r.out.isBlank())
            "No NCI frames in the recent log. Scan a tag (or enable NFC) and try again — " +
                "the HAL logs every frame at NXPLOG_NCIR/NCIX=0x04."
        else r.out
    }

    /** Full dumpsys nfc, saved to a file. Includes the base64/deflate
     *  NFCSNOOP_LOG_SUMMARY blob (the NCI packet ring) for offline decode. */
    private fun nfcsnoop(): String {
        val cmd = "d=/sdcard/ghostwire; mkdir -p \$d; f=\$d/nfcsnoop-\$(date +%s).txt; " +
            "dumpsys nfc > \$f 2>/dev/null; echo \"saved \$f (\$(wc -l < \$f) lines)\"; " +
            "grep -c NFCSNOOP \$f"
        return Root.run(cmd).out +
            "\n\nThe NFCSNOOP_LOG_SUMMARY block is base64+deflate — decode offline with the AOSP " +
            "nfcsnoop parser (packages/apps/Nfc tools) to get the NCI packet history."
    }
}
