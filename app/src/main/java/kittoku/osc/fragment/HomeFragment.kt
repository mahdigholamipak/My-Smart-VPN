package kittoku.osc.fragment

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import kittoku.osc.R
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue
import kittoku.osc.preference.checkPreferences
import kittoku.osc.preference.toastInvalidSetting
import kittoku.osc.repository.SstpServer
import kittoku.osc.repository.VpnRepository
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.ACTION_VPN_DISCONNECT
import kittoku.osc.service.ACTION_VPN_STATUS_CHANGED
import kittoku.osc.service.GeoIpService
import kittoku.osc.service.SstpVpnService
import kittoku.osc.preference.IranBypassHelper

class HomeFragment : Fragment(R.layout.fragment_home) {
    companion object {
        private const val TAG = "HomeFragment"
        private const val CONNECTION_TIMEOUT_MS = 15000L
        private const val MAX_FAILOVER_ATTEMPTS = 15  // Requirement #4: 15 retry attempts
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var tvStatus: TextView
    private lateinit var tvServerInfo: TextView
    private lateinit var tvLatency: TextView
    private lateinit var btnConnect: Button
    private var progressConnecting: android.widget.ProgressBar? = null
    private val vpnRepository = VpnRepository()
    private var servers = mutableListOf<SstpServer>()
    private var currentServerIndex = 0
    private val connectionHandler = Handler(Looper.getMainLooper())
    private var connectionAttemptRunnable: Runnable? = null
    
    // Latency monitoring
    private val latencyHandler = Handler(Looper.getMainLooper())
    private var latencyMonitoringRunnable: Runnable? = null
    private var isLatencyMonitoringActive = false
    
    // Track attempted servers to avoid retrying failed ones
    private val attemptedServers = mutableSetOf<String>()
    
    // Flag to differentiate user disconnect from connection failure
    private var isUserInitiatedDisconnect = false
    
    // Flag to track if we're in failover mode
    private var isFailoverActive = false
    
    // Current connection state
    private var currentState = ConnectionState.DISCONNECTED
    
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra("status") ?: "DISCONNECTED"
            Log.d(TAG, "Received VPN status: $status, isUserDisconnect: $isUserInitiatedDisconnect")
            
            when {
                status == "CONNECTING" -> {
                    currentState = ConnectionState.CONNECTING
                    updateStatusUI("Connecting...")
                }
                status == "CONNECTED" -> {
                    currentState = ConnectionState.CONNECTED
                    isFailoverActive = false
                    attemptedServers.clear()
                    connectionAttemptRunnable?.let { connectionHandler.removeCallbacks(it) }
                    updateStatusUI("CONNECTED")
                    updateServerInfoDisplay()
                    
                    // Fetch real IP and location via GeoIP API
                    fetchRealConnectionInfo()
                }
                status == "DISCONNECTED" -> {
                    currentState = ConnectionState.DISCONNECTED
                    connectionAttemptRunnable?.let { connectionHandler.removeCallbacks(it) }
                    
                    // Only retry if NOT user-initiated disconnect AND failover is active
                    if (!isUserInitiatedDisconnect && isFailoverActive) {
                        Log.d(TAG, "Connection failed, trying next server...")
                        connectToNextServer()
                    } else {
                        updateStatusUI("DISCONNECTED")
                        isUserInitiatedDisconnect = false
                    }
                }
                status.startsWith("ERROR") -> {
                    currentState = ConnectionState.DISCONNECTED
                    connectionAttemptRunnable?.let { connectionHandler.removeCallbacks(it) }
                    
                    // Retry on error if failover is active
                    if (isFailoverActive && !isUserInitiatedDisconnect) {
                        Log.d(TAG, "Error received, trying next server: $status")
                        connectToNextServer()
                    } else {
                        updateStatusUI(status)
                    }
                }
            }
        }
    }

    private val preparationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService(ACTION_VPN_CONNECT)
        } else {
            // User denied VPN permission
            updateStatusUI("VPN permission denied")
            isFailoverActive = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setFragmentResultListener("serverSelection") { _, bundle ->
            val selectedHostname = bundle.getString("selectedHostname")
            val shouldConnect = bundle.getBoolean("shouldConnect", false)
            
            if (selectedHostname != null) {
                prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                
                // Check if currently connected - need to disconnect first
                val isCurrentlyConnected = getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)
                
                // Save selected server to preferences
                setStringPrefValue(selectedHostname, OscPrefKey.HOME_HOSTNAME, prefs)
                setStringPrefValue("vpn", OscPrefKey.HOME_USERNAME, prefs)
                setStringPrefValue("vpn", OscPrefKey.HOME_PASSWORD, prefs)
                
                if (isCurrentlyConnected && shouldConnect) {
                    // ISSUE #4 FIX: Disconnect-then-connect workflow
                    Log.d(TAG, "Switching server: disconnecting first, then connecting to $selectedHostname")
                    
                    // Disconnect current connection
                    isUserInitiatedDisconnect = false  // Not a real disconnect
                    disconnectVpn()
                    
                    // Wait a moment then connect to new server
                    connectionHandler.postDelayed({
                        if (currentState == ConnectionState.DISCONNECTED) {
                            Log.d(TAG, "Connecting to new server: $selectedHostname")
                            connectToServer(selectedHostname)
                        }
                    }, 1000)  // Wait 1 second for disconnect to complete
                } else if (shouldConnect) {
                    // Not connected, just connect directly
                    connectToServer(selectedHostname)
                }
            }
        }
        
        // CRITICAL FIX #4: Initialize Iran Bypass on cold start
        // This ensures the app filter logic works on first launch without user toggling
        initializeIranBypassOnFirstLaunch()
    }
    
    /**
     * Apply Iran Bypass filters on first app launch
     * Fixes the "cold start" bug where logic doesn't work until user toggles manually
     */
    private fun initializeIranBypassOnFirstLaunch() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val isIranBypassEnabled = prefs.getBoolean("IRAN_BYPASS_ENABLED", true)
        val hasInitialized = prefs.getBoolean("IRAN_BYPASS_INITIALIZED", false)
        
        if (isIranBypassEnabled && !hasInitialized) {
            Log.d(TAG, "Applying Iran Bypass on first launch")
            IranBypassHelper.applyIranBypass(requireContext(), prefs, true)
            prefs.edit().putBoolean("IRAN_BYPASS_INITIALIZED", true).apply()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        tvStatus = view.findViewById(R.id.tv_status)
        btnConnect = view.findViewById(R.id.btn_connect)
        
        // Server info TextView
        tvServerInfo = view.findViewById(R.id.tv_server_info) ?: TextView(context).also { }
        
        // Latency display
        tvLatency = view.findViewById(R.id.tv_latency) ?: TextView(context).also { }
        
        // Progress bar for connecting state
        progressConnecting = view.findViewById(R.id.progress_connecting)
        
        val btnServerList = view.findViewById<Button>(R.id.btn_server_list)
        val btnManualConnect = view.findViewById<Button>(R.id.btn_manual_connect)
        val btnSettings = view.findViewById<android.widget.ImageButton>(R.id.btn_settings)

        btnConnect.setOnClickListener {
            when (currentState) {
                ConnectionState.DISCONNECTED -> {
                    isUserInitiatedDisconnect = false
                    loadServersAndConnect()
                }
                ConnectionState.CONNECTING -> {
                    cancelConnection()
                }
                ConnectionState.CONNECTED -> {
                    isUserInitiatedDisconnect = true
                    isFailoverActive = false
                    stopLatencyMonitoring()
                    disconnectVpn()
                }
            }
        }

        btnServerList.setOnClickListener {
            findNavController().navigate(R.id.action_HomeFragment_to_ServerListFragment)
        }
        
        // Manual connect button
        btnManualConnect?.setOnClickListener {
            findNavController().navigate(R.id.action_HomeFragment_to_ManualConnectFragment)
        }
        
        // Settings button
        btnSettings?.setOnClickListener {
            findNavController().navigate(R.id.action_HomeFragment_to_SettingFragment)
        }

        // Check initial state
        syncWithVpnState()
    }


    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - syncing with VPN state")
        
        val filter = IntentFilter(ACTION_VPN_STATUS_CHANGED)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(vpnStatusReceiver, filter)
        
        // FIX: Sync UI with actual VPN service state when returning from other screens
        syncWithVpnState()
        
        // REQUIREMENT #2: Trigger background ping refresh on app launch
        // Only refresh if not connected and not in the middle of connecting
        if (currentState == ConnectionState.DISCONNECTED) {
            refreshPingsInBackground()
        }
    }
    
    /**
     * REQUIREMENT #2: Background ping refresh on app launch
     * Refreshes ping latencies to replace old cached values
     */
    private fun refreshPingsInBackground() {
        val cachedServers = kittoku.osc.repository.ServerCache.loadCachedServers(prefs)
        if (cachedServers.isNullOrEmpty()) {
            Log.d(TAG, "No cached servers to refresh pings for")
            return
        }
        
        Log.d(TAG, "Background ping refresh: ${cachedServers.size} servers")
        
        // Refresh pings in background (silent, no UI updates)
        vpnRepository.measureRealPingsParallel(
            cachedServers,
            onServerUpdated = { _, _ -> },
            onProgress = { _, _ -> },
            onComplete = { sortedServers ->
                // Filter out dead servers and save
                val liveServers = sortedServers.filter { it.realPing > 0 }
                if (liveServers.isNotEmpty()) {
                    kittoku.osc.repository.ServerCache.saveFilteredServersWithPings(prefs, liveServers)
                    Log.d(TAG, "Background ping complete: ${liveServers.size} live servers cached")
                }
            }
        )
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(vpnStatusReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        connectionAttemptRunnable?.let { connectionHandler.removeCallbacks(it) }
    }
    
    /**
     * Sync UI with actual VPN service state
     * This fixes the bug where returning to Home resets UI to DISCONNECTED
     */
    private fun syncWithVpnState() {
        val isConnected = getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)
        Log.d(TAG, "syncWithVpnState: ROOT_STATE = $isConnected")
        
        if (isConnected) {
            currentState = ConnectionState.CONNECTED
            updateStatusUI("CONNECTED")
            updateServerInfoDisplay()
        } else if (currentState != ConnectionState.CONNECTING) {
            // Only update to disconnected if we're not actively connecting
            currentState = ConnectionState.DISCONNECTED
            updateStatusUI("DISCONNECTED")
        }
    }
    
    /**
     * ISSUE #1 FIX: Check network connectivity to prevent crash when offline
     * @return true if network is available, false otherwise
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (e: Exception) {
            Log.e(TAG, "Network check failed: ${e.message}")
            false
        }
    }

    /**
     * ISSUE #1 CRITICAL FIX: Refactored connection logic with proper priority
     * 
     * Priority 1: Last Known Good - Check prefs for last connected server
     * Priority 2: Best Ping - Use sorted list from UI (lowest ping at index 0)
     * Priority 3: Cold Start - Rapid ping top 5 servers, connect to best
     * 
     * Crash Fix: All connections wrapped in try-catch
     * Offline Fix: Check network before attempting to connect
     */
    private fun loadServersAndConnect() {
        try {
            // ISSUE #1 FIX: Check network connectivity FIRST to prevent crash
            if (!isNetworkAvailable()) {
                Log.w(TAG, "No network connection available")
                updateStatusUI("No Internet")
                Toast.makeText(context, "No internet connection. Please check your network.", Toast.LENGTH_LONG).show()
                currentState = ConnectionState.DISCONNECTED
                return
            }
            
            // PRIORITY 1: Try last successfully connected server first
            val lastSuccessful: String? = vpnRepository.getLastSuccessfulServer()
            if (!lastSuccessful.isNullOrBlank()) {
                Log.d(TAG, "Priority 1: Connecting to last good server: $lastSuccessful")
                updateStatusUI("Reconnecting...")
                connectToServer(lastSuccessful)
                return
            }
            
            // PRIORITY 2: Use ping-sorted list (persisted from previous session)
            val sortedServers = kittoku.osc.repository.ServerCache.loadSortedServersWithPings(prefs)
            if (sortedServers != null && sortedServers.isNotEmpty()) {
                // Get best server (lowest positive ping at index 0)
                val bestServer = sortedServers.firstOrNull { it.realPing > 0 } ?: sortedServers.first()
                Log.d(TAG, "Priority 2: Connecting to best ping server: ${bestServer.hostName} (${bestServer.realPing}ms)")
                updateStatusUI("Connecting to fastest server...")
                
                servers.clear()
                servers.addAll(sortedServers)
                currentServerIndex = 0
                attemptedServers.clear()
                isFailoverActive = true
                isUserInitiatedDisconnect = false
                
                connectToServer(bestServer.hostName)
                return
            }
            
            // PRIORITY 3: Cold start - rapid ping top servers first
            Log.d(TAG, "Priority 3: Cold start - loading and pinging servers")
            updateStatusUI("Finding fastest server...")
            coldStartConnect()
            
        } catch (e: Exception) {
            Log.e(TAG, "Connect error: ${e.message}", e)
            updateStatusUI("Connection failed")
            currentState = ConnectionState.DISCONNECTED
        }
    }
    
    /**
     * Connect to a specific server hostname
     * Crash-safe with null checks
     */
    private fun connectToServer(hostname: String) {
        try {
            if (hostname.isBlank()) {
                Log.e(TAG, "Cannot connect: hostname is blank")
                updateStatusUI("Invalid server")
                return
            }
            
            setStringPrefValue(hostname, OscPrefKey.HOME_HOSTNAME, prefs)
            setStringPrefValue("vpn", OscPrefKey.HOME_USERNAME, prefs)
            setStringPrefValue("vpn", OscPrefKey.HOME_PASSWORD, prefs)
            
            isFailoverActive = true
            isUserInitiatedDisconnect = false
            attemptedServers.add(hostname)
            
            // Set timeout for failover
            connectionAttemptRunnable = Runnable {
                Log.d(TAG, "Connection timeout, trying next server")
                if (currentState != ConnectionState.CONNECTED) {
                    tryNextServer()
                }
            }
            connectionHandler.postDelayed(connectionAttemptRunnable!!, CONNECTION_TIMEOUT_MS)
            
            // Start VPN connection
            VpnService.prepare(requireContext())?.also {
                preparationLauncher.launch(it)
            } ?: startVpnService(ACTION_VPN_CONNECT)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to $hostname: ${e.message}", e)
            tryNextServer()
        }
    }
    
    /**
     * REQUIREMENT #1: Cold start with FULL server ping
     * 
     * Must ping ALL servers before connecting to ensure verified latency.
     * Never guess the best server - always verify via ping first.
     */
    private fun coldStartConnect() {
        vpnRepository.fetchSstpServers { newServers ->
            activity?.runOnUiThread {
                try {
                    if (newServers.isEmpty()) {
                        updateStatusUI("No servers available")
                        currentState = ConnectionState.DISCONNECTED
                        return@runOnUiThread
                    }
                    
                    // Save raw servers to cache
                    kittoku.osc.repository.ServerCache.saveServers(prefs, newServers)
                    
                    Log.d(TAG, "Cold start: Pinging ALL ${newServers.size} servers...")
                    updateStatusUI("Testing ${newServers.size} servers...")
                    
                    // REQUIREMENT #1: Ping ALL servers in parallel
                    vpnRepository.measureRealPingsParallel(
                        newServers,
                        onServerUpdated = { _, _ -> }, // No live UI updates needed here
                        onProgress = { current, total ->
                            updateStatusUI("Testing: $current/$total")
                        },
                        onComplete = { sortedServers ->
                            activity?.runOnUiThread {
                                try {
                                    // Filter out dead servers (REQUIREMENT #3: Cache Hygiene)
                                    val liveServers = sortedServers.filter { it.realPing > 0 }
                                    
                                    if (liveServers.isEmpty()) {
                                        Log.e(TAG, "No servers responded to ping")
                                        updateStatusUI("No servers available")
                                        Toast.makeText(context, "No servers are reachable. Please check your network.", Toast.LENGTH_LONG).show()
                                        currentState = ConnectionState.DISCONNECTED
                                        return@runOnUiThread
                                    }
                                    
                                    // Save filtered servers with pings
                                    kittoku.osc.repository.ServerCache.saveFilteredServersWithPings(prefs, liveServers)
                                    
                                    // Sort by ping (lowest first) and get best
                                    val best = liveServers.minByOrNull { it.realPing } ?: liveServers.first()
                                    
                                    Log.d(TAG, "Best server after full ping: ${best.hostName} (${best.realPing}ms)")
                                    updateStatusUI("Connecting to ${best.hostName.take(15)}...")
                                    
                                    servers.clear()
                                    servers.addAll(liveServers)
                                    currentServerIndex = 0
                                    attemptedServers.clear()
                                    isFailoverActive = true
                                    isUserInitiatedDisconnect = false
                                    
                                    connectToServer(best.hostName)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Cold start completion error: ${e.message}", e)
                                    updateStatusUI("Connection failed")
                                    currentState = ConnectionState.DISCONNECTED
                                }
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Cold start error: ${e.message}", e)
                    updateStatusUI("Connection failed")
                    currentState = ConnectionState.DISCONNECTED
                }
            }
        }
    }
    
    /**
     * REQUIREMENT #4: Smart Fallback with Re-ping
     * 
     * When connection fails:
     * 1. Re-ping server list to get latest network status
     * 2. Re-sort by latency
     * 3. Connect to next best server
     * 
     * Repeats up to 15 times, then shows failure message
     */
    private fun tryNextServerWithReping() {
        try {
            currentServerIndex++
            
            // Exit condition: 15 attempts exhausted
            if (currentServerIndex >= MAX_FAILOVER_ATTEMPTS) {
                Log.d(TAG, "15 connection attempts exhausted")
                updateStatusUI("Connection Failed")
                Toast.makeText(context, "Connection failed after 15 attempts. Please try again later.", Toast.LENGTH_LONG).show()
                currentState = ConnectionState.DISCONNECTED
                isFailoverActive = false
                return
            }
            
            // Exit condition: user cancelled
            if (isUserInitiatedDisconnect) {
                Log.d(TAG, "User cancelled connection")
                currentState = ConnectionState.DISCONNECTED
                return
            }
            
            Log.d(TAG, "Retry ${currentServerIndex + 1}/$MAX_FAILOVER_ATTEMPTS: Re-pinging servers...")
            updateStatusUI("Retry ${currentServerIndex + 1}/$MAX_FAILOVER_ATTEMPTS...")
            
            // Re-ping to get latest network status
            vpnRepository.rapidPingServers(servers.take(10)) { freshPingedServers ->
                activity?.runOnUiThread {
                    try {
                        // Re-sort by ping (lowest first), filter out timeouts
                        val sortedServers = freshPingedServers
                            .filter { it.realPing > 0 }
                            .sortedBy { it.realPing }
                        
                        if (sortedServers.isEmpty()) {
                            Log.w(TAG, "No servers responded to ping")
                            tryNextServerWithReping() // Recurse to try again
                            return@runOnUiThread
                        }
                        
                        // Find best server not already attempted
                        val nextBest = sortedServers.firstOrNull { !attemptedServers.contains(it.hostName) }
                        
                        if (nextBest != null) {
                            Log.d(TAG, "Next best server: ${nextBest.hostName} (${nextBest.realPing}ms)")
                            updateStatusUI("Trying ${nextBest.hostName.take(15)}...")
                            connectToServer(nextBest.hostName)
                        } else {
                            // All good servers attempted, try any remaining
                            tryNextServerWithReping()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Re-ping error: ${e.message}", e)
                        tryNextServerWithReping()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failover error: ${e.message}", e)
            updateStatusUI("Connection Failed")
            Toast.makeText(context, "Connection failed. Please try again.", Toast.LENGTH_LONG).show()
            currentState = ConnectionState.DISCONNECTED
        }
    }
    
    /**
     * Legacy tryNextServer for backward compatibility
     * Now calls the smart re-ping version
     */
    private fun tryNextServer() {
        tryNextServerWithReping()
    }
    
    /**
     * Start connection with failover capability for last successful server
     */
    private fun startConnectionWithFailover() {
        connectionAttemptRunnable = Runnable {
            Log.d(TAG, "Last server connection timeout, loading server list")
            if (currentState != ConnectionState.CONNECTED) {
                loadServerListAndConnect()
            }
        }
        connectionHandler.postDelayed(connectionAttemptRunnable!!, CONNECTION_TIMEOUT_MS)
        
        VpnService.prepare(requireContext())?.also {
            preparationLauncher.launch(it)
        } ?: startVpnService(ACTION_VPN_CONNECT)
    }
    
    /**
     * Load server list and connect (fallback path)
     */
    private fun loadServerListAndConnect() {
        val cachedServers = kittoku.osc.repository.ServerCache.loadCachedServers(prefs)
        
        if (cachedServers != null && cachedServers.isNotEmpty()) {
            servers.clear()
            servers.addAll(cachedServers.sortedByDescending { it.smartRank })
            currentServerIndex = 0
            isFailoverActive = true
            startConnectionFlow()
        } else {
            vpnRepository.fetchSstpServers { newServers ->
                activity?.runOnUiThread {
                    if (newServers.isNotEmpty()) {
                        kittoku.osc.repository.ServerCache.saveServers(prefs, newServers)
                        servers.clear()
                        servers.addAll(newServers.sortedByDescending { it.smartRank })
                        currentServerIndex = 0
                        isFailoverActive = true
                        startConnectionFlow()
                    }
                }
            }
        }
    }

    private fun startSingleConnection(hostname: String) {
        isFailoverActive = false
        isUserInitiatedDisconnect = false
        attemptedServers.clear()
        
        updateStatusUI("Preparing...")
        
        // Set up timeout
        connectionAttemptRunnable = Runnable {
            if (currentState == ConnectionState.CONNECTING) {
                updateStatusUI("Connection timed out")
                currentState = ConnectionState.DISCONNECTED
            }
        }
        connectionHandler.postDelayed(connectionAttemptRunnable!!, CONNECTION_TIMEOUT_MS)

        VpnService.prepare(requireContext())?.also {
            preparationLauncher.launch(it)
        } ?: startVpnService(ACTION_VPN_CONNECT)
    }

    private fun startConnectionFlow() {
        // Find next server that hasn't been attempted
        while (currentServerIndex < servers.size) {
            val server = servers[currentServerIndex]
            if (!attemptedServers.contains(server.hostName)) {
                break
            }
            currentServerIndex++
        }
        
        if (currentServerIndex >= servers.size) {
            updateStatusUI("All servers failed")
            isFailoverActive = false
            attemptedServers.clear()
            return
        }

        val server = servers[currentServerIndex]
        attemptedServers.add(server.hostName)
        
        updateStatusUI("Trying ${server.country}...")
        Log.d(TAG, "Attempting connection to: ${server.hostName}")

        setStringPrefValue(server.hostName, OscPrefKey.HOME_HOSTNAME, prefs)
        setStringPrefValue("vpn", OscPrefKey.HOME_USERNAME, prefs)
        setStringPrefValue("vpn", OscPrefKey.HOME_PASSWORD, prefs)

        checkPreferences(prefs)?.also {
            toastInvalidSetting(it, requireContext())
            currentServerIndex++
            startConnectionFlow()
            return
        }

        // Set up timeout for this connection attempt
        connectionAttemptRunnable = Runnable {
            Log.d(TAG, "Connection timeout, trying next server")
            if (isFailoverActive && currentState != ConnectionState.CONNECTED) {
                connectToNextServer()
            }
        }
        connectionHandler.postDelayed(connectionAttemptRunnable!!, CONNECTION_TIMEOUT_MS)

        VpnService.prepare(requireContext())?.also {
            preparationLauncher.launch(it)
        } ?: startVpnService(ACTION_VPN_CONNECT)
    }

    private fun connectToNextServer() {
        currentServerIndex++
        connectionHandler.post { startConnectionFlow() }
    }
    
    private fun cancelConnection() {
        Log.d(TAG, "Canceling connection")
        isUserInitiatedDisconnect = true
        isFailoverActive = false
        connectionAttemptRunnable?.let { connectionHandler.removeCallbacks(it) }
        disconnectVpn()
        updateStatusUI("Cancelled")
        currentState = ConnectionState.DISCONNECTED
    }
    
    private fun disconnectVpn() {
        Log.d(TAG, "Disconnecting VPN")
        val intent = Intent(requireContext(), SstpVpnService::class.java)
        intent.action = ACTION_VPN_DISCONNECT
        requireContext().startService(intent)
    }

    private fun updateStatusUI(status: String) {
        if (!isAdded) return
        
        tvStatus.text = status
        when {
            status.equals("CONNECTED", ignoreCase = true) -> {
                btnConnect.text = "DISCONNECT"
                btnConnect.isEnabled = true
                btnConnect.setBackgroundColor(Color.parseColor("#F44336")) // RED for disconnect
                tvStatus.setTextColor(Color.parseColor("#4CAF50")) // Green
                progressConnecting?.visibility = View.GONE
            }
            status.startsWith("Connecting", ignoreCase = true) || 
            status.startsWith("Trying", ignoreCase = true) ||
            status.startsWith("Preparing", ignoreCase = true) ||
            status.startsWith("Loading", ignoreCase = true) -> {
                btnConnect.text = "CANCEL"
                btnConnect.isEnabled = true
                btnConnect.setBackgroundColor(Color.parseColor("#FF9800")) // Orange
                tvStatus.setTextColor(Color.parseColor("#FF9800")) // Orange
                progressConnecting?.visibility = View.VISIBLE
            }
            else -> {
                btnConnect.text = "CONNECT"
                btnConnect.isEnabled = true
                btnConnect.setBackgroundColor(Color.parseColor("#4CAF50")) // Green
                tvStatus.setTextColor(Color.GRAY)
                progressConnecting?.visibility = View.GONE
            }
        }
    }
    
    private fun updateServerInfoDisplay() {
        if (!isAdded) return
        
        try {
            val hostname = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)
            // Find server info from our list
            val connectedServer = servers.find { it.hostName == hostname }
            if (connectedServer != null) {
                tvServerInfo.text = "${connectedServer.country} | ${connectedServer.ip}"
                tvServerInfo.visibility = View.VISIBLE
            }
            
            // Start latency monitoring when connected
            startLatencyMonitoring(hostname)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating server info", e)
        }
    }
    
    /**
     * Fetch real IP and location using GeoIP API
     * Replaces CSV metadata with actual connection data
     */
    private fun fetchRealConnectionInfo() {
        GeoIpService.fetchGeoInfo { geoInfo ->
            activity?.runOnUiThread {
                if (!isAdded || currentState != ConnectionState.CONNECTED) return@runOnUiThread
                
                geoInfo?.let {
                    tvServerInfo.text = "${it.getLocationString()} | ${it.ip}"
                    tvServerInfo.visibility = View.VISIBLE
                    Log.d(TAG, "Real connection info: ${it.ip} in ${it.getLocationString()}")
                }
            }
        }
    }

    private fun startVpnService(action: String) {
        Log.d(TAG, "Starting VPN service with action: $action")
        val intent = Intent(requireContext(), SstpVpnService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
    }
    
    /**
     * Start periodic latency monitoring using TCP connection time
     */
    private fun startLatencyMonitoring(hostname: String) {
        if (isLatencyMonitoringActive) return
        
        isLatencyMonitoringActive = true
        Log.d(TAG, "Starting latency monitoring for: $hostname")
        
        latencyMonitoringRunnable = object : Runnable {
            override fun run() {
                if (!isLatencyMonitoringActive || currentState != ConnectionState.CONNECTED) {
                    return
                }
                
                // Measure latency in background thread
                Thread {
                    val latency = vpnRepository.measureLatency(hostname)
                    
                    activity?.runOnUiThread {
                        if (isAdded && currentState == ConnectionState.CONNECTED) {
                            updateLatencyDisplay(latency)
                        }
                    }
                }.start()
                
                // Schedule next check in 5 seconds
                if (isLatencyMonitoringActive) {
                    latencyHandler.postDelayed(this, 5000)
                }
            }
        }
        
        // Start immediately
        latencyHandler.post(latencyMonitoringRunnable!!)
    }
    
    /**
     * Stop latency monitoring
     */
    private fun stopLatencyMonitoring() {
        isLatencyMonitoringActive = false
        latencyMonitoringRunnable?.let { latencyHandler.removeCallbacks(it) }
        latencyMonitoringRunnable = null
        
        activity?.runOnUiThread {
            if (isAdded) {
                tvLatency.visibility = View.GONE
            }
        }
    }
    
    /**
     * Update latency display
     */
    private fun updateLatencyDisplay(latencyMs: Long) {
        if (!isAdded) return
        
        if (latencyMs >= 0) {
            val color = when {
                latencyMs < 100 -> "#4CAF50" // Green - Excellent
                latencyMs < 200 -> "#8BC34A" // Light Green - Good
                latencyMs < 500 -> "#FF9800" // Orange - Fair
                else -> "#F44336" // Red - Poor
            }
            tvLatency.text = "⚡ ${latencyMs}ms"
            tvLatency.setTextColor(Color.parseColor(color))
            tvLatency.visibility = View.VISIBLE
        } else {
            tvLatency.text = "⚡ --"
            tvLatency.setTextColor(Color.GRAY)
            tvLatency.visibility = View.VISIBLE
        }
    }
}
