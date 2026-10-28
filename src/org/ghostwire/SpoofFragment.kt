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

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

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

    private lateinit var map: MapView
    private var marker: Marker? = null
    private var mapReady = false
    // Guards the field<->marker loop: set while one side programmatically updates the other.
    private var syncing = false

    // gps + network so both the raw-provider and legacy consumers are covered; the fused
    // provider delegates to these on AOSP, so this reaches getLastLocation too.
    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        // osmdroid needs a user agent and a writable cache (app-internal, no storage perm).
        val cfg = Configuration.getInstance()
        cfg.userAgentValue = requireContext().packageName
        cfg.osmdroidBasePath = File(requireContext().cacheDir, "osmdroid")
        cfg.osmdroidTileCache = File(cfg.osmdroidBasePath, "tiles")
        return inflater.inflate(R.layout.fragment_spoof, container, false)
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        out = view.findViewById(R.id.spoof_out)
        lat = view.findViewById(R.id.spoof_lat)
        lon = view.findViewById(R.id.spoof_lon)
        map = view.findViewById(R.id.spoof_map)
        val hint = view.findViewById<TextView>(R.id.spoof_map_hint)
        view.findViewById<Button>(R.id.spoof_start).setOnClickListener { start() }
        view.findViewById<Button>(R.id.spoof_stop).setOnClickListener { stop() }

        // Typing a coordinate moves the pin (when the map is up).
        val watcher = object : TextWatcher {
            override fun afterTextChanged(e: Editable?) {
                if (syncing || !mapReady) return
                val la = lat.text.toString().trim().toDoubleOrNull()
                val lo = lon.text.toString().trim().toDoubleOrNull()
                if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0)
                    placePin(GeoPoint(la, lo), center = false)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        lat.addTextChangedListener(watcher)
        lon.addTextChangedListener(watcher)

        view.findViewById<Button>(R.id.spoof_locate).setOnClickListener { b ->
            if (map.visibility == View.VISIBLE) {
                map.visibility = View.GONE; hint.visibility = View.GONE
                (b as Button).text = "Locate on map"
            } else {
                initMap()
                map.visibility = View.VISIBLE; hint.visibility = View.VISIBLE
                (b as Button).text = "Hide map"
                centerInitial()
            }
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun initMap() {
        if (mapReady) return
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)          // pinch zoom + two-finger rotate/pan
        map.isTilesScaledToDpi = true
        map.overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS) // dark map
        map.controller.setZoom(16.0)
        // The map sits in a ScrollView, which would otherwise swallow drag/pinch — it
        // only let double-tap-zoom through. While a finger is on the map, tell every
        // ancestor not to intercept, so panning and pinch-to-zoom reach the map.
        map.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false   // let osmdroid handle the gesture
        }
        // Tap to drop/move the pin.
        val tap = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { placePin(p, center = false); return true }
            override fun longPressHelper(p: GeoPoint) = false
        })
        map.overlays.add(tap)
        mapReady = true
    }

    /** Move the pin to [p], sync the lat/lon fields, and optionally recenter the map. */
    private fun placePin(p: GeoPoint, center: Boolean) {
        if (marker == null) {
            marker = Marker(map).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                isDraggable = true
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDrag(m: Marker) { setFields(m.position) }
                    override fun onMarkerDragEnd(m: Marker) { setFields(m.position); m.showInfoWindow() }
                    override fun onMarkerDragStart(m: Marker) {}
                })
            }
            map.overlays.add(marker)
        }
        marker!!.position = p
        marker!!.title = "%.6f, %.6f".format(p.latitude, p.longitude)
        setFields(p)
        if (center) map.controller.animateTo(p)
        map.invalidate()
    }

    private fun setFields(p: GeoPoint) {
        syncing = true
        lat.setText("%.6f".format(p.latitude))
        lon.setText("%.6f".format(p.longitude))
        syncing = false
    }

    /** First show: pin the typed coords if valid, else drop on the last known location. */
    private fun centerInitial() {
        val la = lat.text.toString().trim().toDoubleOrNull()
        val lo = lon.text.toString().trim().toDoubleOrNull()
        if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) {
            placePin(GeoPoint(la, lo), center = true); return
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val lmgr = requireContext().getSystemService(LocationManager::class.java)
            val loc = try {
                lmgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lmgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (e: SecurityException) { null }
            if (loc != null) { placePin(GeoPoint(loc.latitude, loc.longitude), center = true); return }
        }
        map.controller.setCenter(GeoPoint(20.0, 0.0)) // world view until a point is chosen
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

    override fun onResume() {
        super.onResume(); if (mapReady) map.onResume()
    }

    override fun onPause() {
        super.onPause(); if (mapReady) map.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView(); stop()
    }
}
