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

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * GhostWire shell. The bottom bar switches capability domain (always visible, one
 * tap); within a domain the top tabs switch screens. Each domain in [domains] owns
 * a list of (title, fragment factory) — adding a capability is one line here plus
 * its fragment, and if it is a new domain, one item in res/menu/bottom_nav.xml.
 */
class MainActivity : AppCompatActivity() {

    // Keys line up with the item ids in res/menu/bottom_nav.xml (see domainFor()).
    private val domains: Map<String, List<Pair<String, () -> Fragment>>> = linkedMapOf(
        "Cellular" to listOf(
            "SIM" to { SimFragment() },
            "Recon" to { ReconFragment() },
            "Guard" to { GuardFragment() },
            "Walk" to { WalkFragment() },
            "Map" to { MapFragment() },
            "DIAG" to { DiagFragment() },
        ),
        "NFC" to listOf("Walrus" to { WalrusFragment() }, "NCI" to { NciFragment() }),
        "GNSS" to listOf("Spoof" to { SpoofFragment() }),
        "SDR Labs" to listOf("SDR" to { SdrFragment() }),
        "Wireless" to listOf(
            "BT Scan" to { BtScanFragment() },
            "Bluetooth" to { BtFragment() },
            "Wi-Fi" to { PlaceholderFragment("Wi-Fi — coming soon") },
        ),
    )

    private lateinit var toolbar: Toolbar
    private lateinit var pager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private var mediator: TabLayoutMediator? = null
    private var tabs: List<Pair<String, () -> Fragment>> = domains.values.first()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark is the default look; cream is the light (MODE_NIGHT_NO) variant.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        pager = findViewById(R.id.pager)
        tabLayout = findViewById(R.id.tabs)

        findViewById<BottomNavigationView>(R.id.bottom_nav).setOnItemSelectedListener { item ->
            domainFor(item.itemId)?.let { showDomain(it); true } ?: false
        }
        // Selecting the item fires the listener, which calls showDomain().
        findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.domain_cellular
        handleTagIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTagIntent(intent)
    }

    /** Capture a tag the OS dispatched to us (even from closed), but only while
     *  auto-scan is on. Saved straight to the store; the Walrus tab reloads it. */
    private fun handleTagIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_NDEF_DISCOVERED
        ) return
        if (!AutoScanService.isEnabled(this)) return
        @Suppress("DEPRECATION")
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        Thread {
            val card = runCatching { NfcCapture.read(tag, Mifare.allKeys(this)) }.getOrNull()
                ?: return@Thread
            val store = CardStore(this)
            val list = store.load()
            list.add(0, card)
            store.save(list)
        }.start()
    }

    private fun domainFor(itemId: Int): String? = when (itemId) {
        R.id.domain_cellular -> "Cellular"
        R.id.domain_nfc -> "NFC"
        R.id.domain_gnss -> "GNSS"
        R.id.domain_sdr -> "SDR Labs"
        R.id.domain_wifi -> "Wireless"
        else -> null
    }

    private fun showDomain(name: String) {
        tabs = domains[name] ?: return
        toolbar.title = name
        mediator?.detach()
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = tabs.size
            override fun createFragment(position: Int) = tabs[position].second()
        }
        // Hide the tab strip when a domain has a single screen — nothing to switch.
        tabLayout.tabMode = if (tabs.size > 1) TabLayout.MODE_SCROLLABLE else TabLayout.MODE_FIXED
        tabLayout.visibility = if (tabs.size > 1) View.VISIBLE else View.GONE
        mediator = TabLayoutMediator(tabLayout, pager) { tab, pos -> tab.text = tabs[pos].first }
            .also { it.attach() }
    }
}
