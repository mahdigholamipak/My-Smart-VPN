package kittoku.osc.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.Lifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kittoku.osc.R
import kittoku.osc.adapter.ServerListAdapter
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.repository.ServerCache
import kittoku.osc.repository.SstpServer
import kittoku.osc.repository.VpnRepository
import kittoku.osc.service.ACTION_VPN_STATUS_CHANGED

class ServerListFragment : Fragment(R.layout.fragment_server_list) {
    companion object {
        private const val TAG = "ServerListFragment"
        
        // Static flag to track if we've pinged this session (survives view recreation)
        private var hasPingedThisSession = false
        private var lastPingedServers = mutableListOf<SstpServer>()
    }
    
    private lateinit var serverListAdapter: ServerListAdapter
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var txtStatus: TextView
    private lateinit var prefs: SharedPreferences
    private var countrySpinner: Spinner? = null
    private val vpnRepository = VpnRepository()
    
    // Current server list (before filtering)
    private var allServers = mutableListOf<SstpServer>()
    
    // Track current connection status
    private var currentStatus = "DISCONNECTED"
    
    // Flag to track if this is a manual refresh (triggers new ping)
    private var isManualRefresh = false
    
    // Flag to track if this is a fresh CSV import (triggers new ping)
    private var isFreshImport = false

    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("status")?.let { status ->
                Log.d(TAG, "Received VPN status broadcast: $status")
                currentStatus = status
                updateStatusUI(status)
                updateConnectedServerHighlight()
            }
        }
    }
    
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
        if (key == OscPrefKey.ROOT_STATE.name) {
            val isConnected = getBooleanPrefValue(OscPrefKey.ROOT_STATE, sharedPrefs)
            val newStatus = if (isConnected) "CONNECTED" else "DISCONNECTED"
            Log.d(TAG, "ROOT_STATE changed: $isConnected -> status: $newStatus")
            
            if (currentStatus != newStatus && currentStatus != "CONNECTING") {
                currentStatus = newStatus
                activity?.runOnUiThread {
                    updateStatusUI(newStatus)
                    updateConnectedServerHighlight()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        
        // Initialize views
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout)
        txtStatus = view.findViewById(R.id.txtStatus)
        countrySpinner = view.findViewById(R.id.spinner_country)
        val serversRecyclerView = view.findViewById<RecyclerView>(R.id.servers_recycler_view)
        serversRecyclerView.layoutManager = LinearLayoutManager(context)

        // Initialize adapter with connected server highlighting
        serverListAdapter = ServerListAdapter(mutableListOf()) { server ->
            Log.d(TAG, "Server selected: ${server.hostName}")
            setFragmentResult("serverSelection", bundleOf("selectedHostname" to server.hostName))
            findNavController().navigateUp()
        }
        serversRecyclerView.adapter = serverListAdapter

        // Setup pull-to-refresh
        swipeRefreshLayout.setOnRefreshListener { 
            Log.d(TAG, "Manual refresh triggered")
            isManualRefresh = true
            loadServersWithPing()  // Manual refresh always pings
        }

        // Set initial status
        val isCurrentlyConnected = getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)
        currentStatus = if (isCurrentlyConnected) "CONNECTED" else "DISCONNECTED"
        updateStatusUI(currentStatus)
        
        // ISSUE #5 FIX: Only load servers, don't auto-ping on initial view
        // Show cached/previous pings if available
        loadServersWithoutPing()
    }

    /**
     * ISSUE #5 FIX: Load servers WITHOUT triggering new ping measurement
     * Shows previous ping results if available
     */
    private fun loadServersWithoutPing() {
        Log.d(TAG, "Loading servers WITHOUT pinging (hasPingedThisSession: $hasPingedThisSession)")
        swipeRefreshLayout.isRefreshing = true
        
        // If we have pinged servers from this session, show those
        if (hasPingedThisSession && lastPingedServers.isNotEmpty()) {
            Log.d(TAG, "Using session-cached pinged servers (${lastPingedServers.size})")
            allServers.clear()
            allServers.addAll(lastPingedServers)
            moveConnectedServerToTop()
            serverListAdapter.updateData(allServers)
            swipeRefreshLayout.isRefreshing = false
            setupCountryFilter()
            updateConnectedServerHighlight()
            txtStatus.text = "✓ ${allServers.size} servers (cached pings)"
            return
        }
        
        // Load from cache without pinging
        val cachedServers = ServerCache.loadCachedServers(prefs)
        if (cachedServers != null && cachedServers.isNotEmpty()) {
            allServers.clear()
            allServers.addAll(cachedServers)
            moveConnectedServerToTop()
            serverListAdapter.updateData(allServers)
            swipeRefreshLayout.isRefreshing = false
            setupCountryFilter()
            updateConnectedServerHighlight()
            txtStatus.text = "Pull to refresh & measure pings"
        } else {
            // No cache - must fetch and ping
            isFreshImport = true
            loadServersWithPing()
        }
    }

    /**
     * Load servers AND trigger ping measurement
     * Called on: manual pull-to-refresh OR fresh CSV import
     */
    private fun loadServersWithPing() {
        Log.d(TAG, "Loading servers WITH pinging (manual: $isManualRefresh, fresh: $isFreshImport)")
        swipeRefreshLayout.isRefreshing = true
        
        val isConnected = getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)
        
        // Check if we should fetch from remote
        if (ServerCache.shouldFetchRemote(prefs, isManualRefresh, isConnected) || isFreshImport) {
            Log.d(TAG, "Fetching from remote server")
            txtStatus.text = "Fetching server list..."
            isFreshImport = true  // Mark as fresh import to trigger ping
            
            vpnRepository.fetchSstpServers { servers ->
                activity?.runOnUiThread {
                    if (servers.isNotEmpty()) {
                        ServerCache.saveServers(prefs, servers)
                    }
                    processServersAndMeasurePing(servers)
                }
            }
        } else {
            // Load from cache and ping
            val cachedServers = ServerCache.loadCachedServers(prefs)
            if (cachedServers != null && cachedServers.isNotEmpty()) {
                processServersAndMeasurePing(cachedServers)
            } else {
                vpnRepository.fetchSstpServers { servers ->
                    activity?.runOnUiThread {
                        if (servers.isNotEmpty()) {
                            ServerCache.saveServers(prefs, servers)
                        }
                        processServersAndMeasurePing(servers)
                    }
                }
            }
        }
        
        isManualRefresh = false
        isFreshImport = false
    }
    
    /**
     * CRITICAL FIX: Show server list IMMEDIATELY, then update pings live
     * 
     * - Displays all servers instantly (with ping showing "...")
     * - Pings run in parallel in background
     * - Each row updates live as its ping arrives
     * - Final sort happens after all pings complete
     */
    private fun processServersAndMeasurePing(servers: List<SstpServer>) {
        if (servers.isEmpty()) {
            Log.w(TAG, "No servers to process")
            swipeRefreshLayout.isRefreshing = false
            txtStatus.text = "No servers available"
            return
        }
        
        // CRITICAL: Show list IMMEDIATELY without waiting for pings
        allServers.clear()
        allServers.addAll(servers)
        moveConnectedServerToTop()
        serverListAdapter.updateData(allServers)
        swipeRefreshLayout.isRefreshing = false
        setupCountryFilter()
        updateConnectedServerHighlight()
        
        Log.d(TAG, "List displayed immediately. Starting parallel ping for ${servers.size} servers")
        txtStatus.text = "Pinging: 0/${servers.size}"
        
        // Start parallel ping measurement with live updates
        vpnRepository.measureRealPingsParallel(
            servers,
            onServerUpdated = { index, updatedServer ->
                // Live update: Replace server at index with updated ping
                if (index < allServers.size) {
                    val originalServer = allServers.find { it.hostName == updatedServer.hostName }
                    val actualIndex = allServers.indexOf(originalServer)
                    if (actualIndex >= 0) {
                        allServers[actualIndex] = updatedServer
                        serverListAdapter.notifyItemChanged(actualIndex)
                    }
                }
            },
            onProgress = { current, total ->
                txtStatus.text = "Pinging: $current/$total"
            },
            onComplete = { sortedServers ->
                allServers.clear()
                allServers.addAll(sortedServers)
                
                // Move connected server to top after sorting
                moveConnectedServerToTop()
                
                // ISSUE #5 FIX: Save to session cache so pings survive view recreation
                lastPingedServers.clear()
                lastPingedServers.addAll(allServers)
                hasPingedThisSession = true
                
                // ISSUE #2 FIX: Persist pings to survive app restarts
                ServerCache.saveSortedServersWithPings(prefs, allServers)
                
                serverListAdapter.updateData(allServers)
                updateConnectedServerHighlight()
                
                txtStatus.text = "✓ Sorted by lowest ping (${allServers.size} servers)"
            }
        )
    }
    
    /**
     * Move connected server to top of list (Index 0)
     */
    private fun moveConnectedServerToTop() {
        val isConnected = getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)
        if (!isConnected) return
        
        try {
            val connectedHostname = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)
            val connectedIndex = allServers.indexOfFirst { it.hostName == connectedHostname }
            
            if (connectedIndex > 0) {
                val server = allServers.removeAt(connectedIndex)
                allServers.add(0, server)
                Log.d(TAG, "Moved connected server to top: ${server.hostName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error moving connected server to top", e)
        }
    }
    
    /**
     * Setup country filter spinner with unique countries
     */
    private fun setupCountryFilter() {
        countrySpinner?.let { spinner ->
            val countries = serverListAdapter.getUniqueCountries()
            val countryNames = mutableListOf("All Countries")
            countryNames.addAll(countries.map { "${it.second} (${it.first})" })
            
            val adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                countryNames
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
            
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position == 0) {
                        // All countries
                        serverListAdapter.filterByCountry(null)
                    } else {
                        val selectedCountry = countries[position - 1]
                        serverListAdapter.filterByCountry(selectedCountry.first)
                    }
                }
                
                override fun onNothingSelected(parent: AdapterView<*>?) {
                    serverListAdapter.filterByCountry(null)
                }
            }
        }
    }
    
    /**
     * Update the connected server highlighting in the adapter
     */
    private fun updateConnectedServerHighlight() {
        if (!isAdded) return
        
        val isConnected = getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)
        if (isConnected) {
            try {
                val connectedHostname = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)
                serverListAdapter.setConnectedServer(connectedHostname)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting connected hostname", e)
                serverListAdapter.setConnectedServer(null)
            }
        } else {
            serverListAdapter.setConnectedServer(null)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - registering receivers")
        
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            vpnStatusReceiver,
            IntentFilter(ACTION_VPN_STATUS_CHANGED)
        )
        
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        
        // Refresh status and highlighting
        val isCurrentlyConnected = getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)
        val status = if (isCurrentlyConnected) "CONNECTED" else "DISCONNECTED"
        if (currentStatus != "CONNECTING") {
            currentStatus = status
            updateStatusUI(status)
        }
        updateConnectedServerHighlight()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause - unregistering receivers")
        
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(vpnStatusReceiver)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun updateStatusUI(status: String) {
        Log.d(TAG, "Updating status UI: $status")
        
        if (!isAdded) return
        
        when {
            status == "CONNECTING" -> {
                txtStatus.text = "Connecting..."
                txtStatus.setTextColor(Color.parseColor("#FF8C00"))
                txtStatus.setBackgroundColor(Color.parseColor("#FFF3CD"))
            }
            status == "CONNECTED" -> {
                txtStatus.text = "✓ Secured"
                txtStatus.setTextColor(Color.parseColor("#155724"))
                txtStatus.setBackgroundColor(Color.parseColor("#D4EDDA"))
            }
            status == "DISCONNECTED" -> {
                txtStatus.text = "Idle"
                txtStatus.setTextColor(Color.parseColor("#6C757D"))
                txtStatus.setBackgroundColor(Color.parseColor("#E9ECEF"))
            }
            status.startsWith("ERROR") -> {
                txtStatus.text = status
                txtStatus.setTextColor(Color.parseColor("#721C24"))
                txtStatus.setBackgroundColor(Color.parseColor("#F8D7DA"))
            }
            else -> {
                txtStatus.text = status
                txtStatus.setTextColor(Color.GRAY)
                txtStatus.setBackgroundColor(Color.parseColor("#E0E0E0"))
            }
        }
    }
}
