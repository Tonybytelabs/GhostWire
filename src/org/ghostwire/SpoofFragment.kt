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

import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * GNSS location spoof. The GNSS *chip* cannot be made to report a false fix — it is a
 * receiver, it computes position from the real satellites it hears, and the engine is
 * behind signed firmware (and RF-transmit spoofing needs external hardware). What *is*
 * reachable, device-wide, is the Android mock-location framework: self-grant the
 * mock-location appop as root, then publish a chosen fix on the GPS + network + fused
 * providers so every app using the standard location APIs sees it. See
 * .claude/research/nethunter-radio-pentest.md section 6.
 */
class SpoofFragment : Fragment() {

    private lateinit var out: TextView
    private lateinit var lat: EditText
    private lateinit var lon: EditText
    private var pushing = false
    private var thread: Thread? = null

    // gps + network so both the raw-provider and legacy consumers are covered; the fused
    // provider delegates to these on AOSP, so this reaches getLastLocation too.
    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_spoof, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        out = view.findViewById(R.id.spoof_out)
        lat = view.findViewById(R.id.spoof_lat)
        lon = view.findViewById(R.id.spoof_lon)
        view.findViewById<Button>(R.id.spoof_start).setOnClickListener { start() }
        view.findViewById<Button>(R.id.spoof_stop).setOnClickListener { stop() }
    }

    private fun lm() = requireContext().getSystemService(LocationManager::class.java)

    private fun start() {
        val la = lat.text.toString().trim().toDoubleOrNull()
        val lo = lon.text.toString().trim().toDoubleOrNull()
        if (la == null || lo == null || la !in -90.0..90.0 || lo !in -180.0..180.0) {
            out.text = "Enter a valid latitude (-90..90) and longitude (-180..180)."
            return
        }
        if (pushing) stop()
        // self-grant the mock-location appop so addTestProvider is allowed app-wide.
        val grant = Root.run("appops set --uid " + requireContext().applicationInfo.uid +
            " android:mock_location allow 2>&1; " +
            "appops set org.ghostwire android:mock_location allow 2>&1")
        val mgr = lm()
        for (p in providers) {
            try {
                runCatching { mgr.removeTestProvider(p) }
                mgr.addTestProvider(
                    p, false, false, false, false, true, true, true, 1, 1
                )
                mgr.setTestProviderEnabled(p, true)
            } catch (e: Exception) {
                out.text = "addTestProvider($p) failed: ${e.message}\n\n" +
                    "Select GhostWire as the mock-location app in Developer options, or check root.\n" +
                    grant.out
                return
            }
        }
        pushing = true
        thread = Thread {
            while (pushing) {
                for (p in providers) {
                    try {
                        val loc = Location(p).apply {
                            latitude = la; longitude = lo; altitude = 0.0
                            accuracy = 3f; time = System.currentTimeMillis()
                            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                            bearing = 0f; speed = 0f
                        }
                        lm().setTestProviderLocation(p, loc)
                    } catch (_: Exception) { }
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }.also { it.start() }
        out.text = "Spoofing $la, $lo on ${providers.joinToString(", ")} (1 Hz).\n" +
            "Every app using the standard location APIs now sees this fix. Stop to restore real GPS."
    }

    private fun stop() {
        pushing = false
        thread?.interrupt(); thread = null
        val mgr = lm()
        for (p in providers) runCatching { mgr.setTestProviderEnabled(p, false) }
            .also { runCatching { mgr.removeTestProvider(p) } }
        out.text = "Stopped. Real location restored."
    }

    override fun onDestroyView() {
        super.onDestroyView(); stop()
    }
}
