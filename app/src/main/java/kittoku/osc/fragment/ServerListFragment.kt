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
import androidx.fragment.app.activityViewModels
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
import kittoku.osc.repository.ServerSorter
import kittoku.osc.repository.SstpServer
import kittoku.osc.repository.VpnRepository
import kittoku.osc.service.ACTION_VPN_STATUS_CHANGED
import kittoku.osc.viewmodel.ServerViewModel

class ServerListFragment : Fragment(R.layout.fragment_server_list) {
    companion object {
        private const val TAG = "ServerListFragment"
        
        // Static flag to track if we've pinged this session (survives view recreation)
        private var hasPingedThisSession = false
        private var lastPingedServers = mutableListOf<SstpServer>()
    }
    
    // Activity-scoped ViewModel for reactive server list updates
    private val serverViewModel: ServerViewModel by activityViewModels()
    
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
    
    /**
     * REQUIREMENT #1: Real-time UI reactivity receiver
     * Receives ping updates from HomeFragment background refresh
     * Updates individual server items and re-sorts list in real-time
     */
    private val pingUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                kittoku.osc.repository.PingUpdateManager.ACTION_PING_UPDATE -> {
                    // Single server ping update - update that item in list
                    val serverJson = intent.getStringExtra(kittoku.osc.repository.PingUpdateManager.EXTRA_SERVER_JSON)
                    if (serverJson != null) {
                        val updatedServer = kittoku.osc.repository.PingUpdateManager.parseServer(serverJson)
                        if (updatedServer != null) {
                            updateServerInList(updatedServer)
                        }
                    }
                    
                    // Progress update
                    val progress = intent.getIntExtra(kittoku.osc.repository.PingUpdateManager.EXTRA_PROGRESS, -1)
                    val total = intent.getIntExtra(kittoku.osc.repository.PingUpdateManager.EXTRA_TOTAL, -1)
                    if (progress > 0 && total > 0) {
                        txtStatus.text = "Refreshing: $progress/$total"
                    }
                }
                
                kittoku.osc.repository.PingUpdateManager.ACTION_PING_COMPLETE -> {
                    // All pings complete - reload and re-sort list
                    val serversJson = intent.getStringExtra(kittoku.osc.repository.PingUpdateManager.EXTRA_SERVER_JSON)
                    if (serversJson != null) {
                        val sortedServers = kittoku.osc.repository.PingUpdateManager.parseServerList(serversJson)
                        if (sortedServers != null) {
                            Log.d(TAG, "Ping complete broadcast: ${sortedServers.size} servers")
                            allServers.clear()
                            allServers.addAll(sortedServers.sortedBy { it.realPing })
                            moveConnectedServerToTop()
                            serverListAdapter.updateData(allServers)
                            txtStatus.text = "✓ Updated (${allServers.size} servers)"
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Update a single server in the list (for real-time ping updates)
     */
    private fun updateServerInList(updatedServer: SstpServer) {
        val index = allServers.indexOfFirst { it.hostName == updatedServer.hostName }
        if (index >= 0) {
            allServers[index] = updatedServer
            serverListAdapter.notifyItemChanged(index)
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
            
            // Pass hostname and shouldConnect flag to HomeFragment
            setFragmentResult("serverSelection", bundleOf(
                "selectedHostname" to server.hostName,
                "shouldConnect" to true  // Always initiate connection after selection
            ))
            
            findNavController().navigateUp()
        }
        serversRecyclerView.adapter = serverListAdapter

        // CRITICAL: Observe ServerViewModel for REACTIVE UI updates
        // This ensures list updates immediately when data changes (no navigation required)
        observeServerViewModel()

        // Setup pull-to-refresh - now uses ViewModel
        swipeRefreshLayout.setOnRefreshListener { 
            Log.d(TAG, "Manual refresh triggered (force refresh)")
            isManualRefresh = true
            serverViewModel.forceRefresh()
        }

        // Set initial status
        val isCurrentlyConnected = getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)
        currentStatus = if (isCurrentlyConnected) "CONNECTED" else "DISCONNECTED"
        updateStatusUI(currentStatus)
        
        // Load initial data from ViewModel (cached or fetch)
        loadServersWithoutPing()
    }
    
    /**
     * REACTIVE UI: Observe ServerViewModel LiveData
     * When ViewModel emits new data, UI updates INSTANTLY
     * User NEVER has to navigate out/back to see changes
     */
    private fun observeServerViewModel() {
        // Observe server list - updates immediately when data changes
        serverViewModel.servers.observe(viewLifecycleOwner) { servers ->
            if (servers.isNotEmpty()) {
                Log.d(TAG, "ViewModel emitted ${servers.size} servers - updating UI instantly")
                allServers.clear()
                allServers.addAll(servers)  // Already sorted by QoS in ViewModel
                moveConnectedServerToTop()
                serverListAdapter.updateData(allServers)
                setupCountryFilter()
                updateConnectedServerHighlight()
                txtStatus.text = "✓ ${servers.size} servers (QoS sorted)"
            }
        }
        
        // Observe loading state
        serverViewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            swipeRefreshLayout.isRefreshing = isLoading
        }
        
        // Observe ping progress
        serverViewModel.pingProgress.observe(viewLifecycleOwner) { (current, total) ->
            txtStatus.text = "Pinging: $current / $total"
        }
        
        // Observe errors
        serverViewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                txtStatus.text = "Error: $it"
                serverViewModel.clearError()
            }
        }
    }

    /**
     * JUST-IN-TIME SORTING (Task 1)
     * 
     * Load servers WITHOUT network fetch, but WITH fresh QoS sorting.
     * Called on fragment open/resume to ensure list reflects current best options.
     * 
     * Formula: QualityScore = 0.6 × NormSpeed + 0.4 × NormPing
     * where NormSpeed = (Speed / (Sessions + 1)) normalized to 0-1
     */
    private fun loadServersWithoutPing() {
        Log.d(TAG, "JUST-IN-TIME: Re-sorting on fragment open (hasPingedThisSession: $hasPingedThisSession)")
        swipeRefreshLayout.isRefreshing = true
        
        // PRIORITY 1: If we have pinged servers from this session, show those
        if (hasPingedThisSession && lastPingedServers.isNotEmpty()) {
            Log.d(TAG, "Using session-cached pinged servers (${lastPingedServers.size})")
            // FRESH SORT: Re-calculate Quality Scores even for session-cached data
            val sorted = ServerSorter.sortByScore(lastPingedServers)
            allServers.clear()
            allServers.addAll(sorted)
            moveConnectedServerToTop()
            serverListAdapter.updateData(allServers)
            swipeRefreshLayout.isRefreshing = false
            setupCountryFilter()
            updateConnectedServerHighlight()
            txtStatus.text = "✓ ${allServers.size} servers (QoS sorted)"
            return
        }
        
        // PRIORITY 2: Load cache and apply FRESH QoS sorting
        val sortedServers = ServerCache.loadSortedServersWithPings(prefs)
        if (sortedServers != null && sortedServers.isNotEmpty()) {
            Log.d(TAG, "JUST-IN-TIME: Re-sorting ${sortedServers.size} cached servers")
            // FRESH SORT: Don't rely on pre-sorted cache, recalculate now
            val qosSorted = ServerSorter.sortByScore(sortedServers)
            allServers.clear()
            allServers.addAll(qosSorted)
            moveConnectedServerToTop()
            serverListAdapter.updateData(allServers)
            swipeRefreshLayout.isRefreshing = false
            setupCountryFilter()
            updateConnectedServerHighlight()
            txtStatus.text = "✓ ${allServers.size} servers (QoS sorted)"
            return
        }
        
        // PRIORITY 3: Load raw cache without pings (fallback)
        val cachedServers = ServerCache.loadCachedServers(prefs)
        if (cachedServers != null && cachedServers.isNotEmpty()) {
            Log.d(TAG, "Using raw cache: ${cachedServers.size} servers")
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
            
            vpnRepository.fetchSstpServers(prefs) { servers ->
                activity?.runOnUiThread {
                    // NOTE: Servers already saved atomically by fetchSstpServers
                    processServersAndMeasurePing(servers)
                }
            }
        } else {
            // Load from cache and ping
            val cachedServers = ServerCache.loadCachedServers(prefs)
            if (cachedServers != null && cachedServers.isNotEmpty()) {
                processServersAndMeasurePing(cachedServers)
            } else {
                vpnRepository.fetchSstpServers(prefs) { servers ->
                    activity?.runOnUiThread {
                        // NOTE: Servers already saved atomically by fetchSstpServers
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
                
                // ISSUE #3 FIX: Persist pings with dead server filtering
                ServerCache.saveFilteredServersWithPings(prefs, allServers)
                
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
        
        // REQUIREMENT #1: Register ping update receiver for real-time UI updates
        val pingFilter = IntentFilter().apply {
            addAction(kittoku.osc.repository.PingUpdateManager.ACTION_PING_UPDATE)
            addAction(kittoku.osc.repository.PingUpdateManager.ACTION_PING_COMPLETE)
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            pingUpdateReceiver,
            pingFilter
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
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(pingUpdateReceiver)
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
