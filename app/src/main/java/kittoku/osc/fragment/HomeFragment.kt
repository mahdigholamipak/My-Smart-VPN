package kittoku.osc.fragment

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
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
import kittoku.osc.repository.ServerSorter
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.ACTION_VPN_DISCONNECT
import kittoku.osc.service.ACTION_VPN_STATUS_CHANGED
import kittoku.osc.service.GeoIpService
import kittoku.osc.service.SstpVpnService
import kittoku.osc.preference.IranBypassHelper
import kittoku.osc.viewmodel.SharedConnectionViewModel
import kittoku.osc.viewmodel.ServerViewModel

class HomeFragment : Fragment(R.layout.fragment_home) {
    companion object {
        private const val TAG = "HomeFragment"
        private const val CONNECTION_TIMEOUT_MS = 15000L
        private const val MAX_FAILOVER_ATTEMPTS = 15  // Requirement #4: 15 retry attempts
        
        // SMART REFRESH: Cooldown to prevent ping spam on screen navigation
        private const val PING_COOLDOWN_MS = 60 * 1000L  // 60 seconds
        private var lastPingTimestamp = 0L  // Static to survive fragment recreation
    }
    
    // Activity-scoped ViewModels - shared with other fragments
    private val connectionViewModel: SharedConnectionViewModel by activityViewModels()
    private val serverViewModel: ServerViewModel by activityViewModels()

    private lateinit var prefs: SharedPreferences
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusPrev: TextView  // Previous status for teleprompter effect
    private lateinit var tvServerInfo: TextView
    private lateinit var tvLatency: TextView
    private lateinit var btnConnect: Button
    private var progressConnecting: android.widget.ProgressBar? = null
    private var currentStatusText = ""  // Track current status for teleprompter
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
    
    // SMART RETRY: Track failed servers to purge them from this session
    private val failedServersThisSession = mutableSetOf<String>()
    
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
                    // Update SharedViewModel - triggers UI update in ALL fragments
                    val hostname = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)
                    connectionViewModel.setConnecting(hostname, isManual = false)
                    updateStatusUI("Connecting...")
                }
                status == "CONNECTED" -> {
                    currentState = ConnectionState.CONNECTED
                    isFailoverActive = false
                    attemptedServers.clear()
                    connectionAttemptRunnable?.let { connectionHandler.removeCallbacks(it) }
                    
                    // Update SharedViewModel - triggers UI update in ALL fragments
                    val serverName = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)
                    connectionViewModel.setConnected(serverName)
                    
                    // Mark server as successful
                    vpnRepository.markServerSuccess(prefs, serverName)
                    
                    updateStatusUI("CONNECTED")
                    updateServerInfoDisplay()
                    
                    // Fetch real IP and location via GeoIP API
                    fetchRealConnectionInfo()
                }
                status == "DISCONNECTED" -> {
                    currentState = ConnectionState.DISCONNECTED
                    connectionAttemptRunnable?.let { connectionHandler.removeCallbacks(it) }
                    
                    // Update SharedViewModel - triggers UI update in ALL fragments
                    connectionViewModel.setDisconnected()
                    
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
                    
                    // SMART RETRY: Suppress visible errors during auto-connect failover
                    if (isFailoverActive && !isUserInitiatedDisconnect) {
                        Log.d(TAG, "SMART RETRY: Suppressing error during failover: $status")
                        
                        // Purge this server from session - it won't be selected again
                        val failedHostname = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)
                        failedServersThisSession.add(failedHostname)
                        
                        // PERMANENTLY remove from cache so it doesn't appear in ServerListFragment
                        kittoku.osc.repository.ServerCache.removeServerFromCache(prefs, failedHostname)
                        Log.d(TAG, "SMART RETRY: Purged server from session AND cache: $failedHostname")
                        
                        // Show reassuring message instead of error
                        updateStatusUI("Server unreachable. Finding a better server for you... 🚀")
                        
                        // Auto-retry with next server
                        connectToNextServer()
                    } else {
                        // Not in failover mode - show error to user
                        connectionViewModel.setDisconnected()
                        connectionViewModel.setError(status)
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
        
        // Request notification permission for Android 13+
        requestNotificationPermission()
    }
    
    /**
     * Request POST_NOTIFICATIONS permission for Android 13+ (API 33)
     * Without this, notifications won't show until user manually enables in settings
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    
    // Launcher for notification permission request
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Notification permission granted")
        } else {
            Log.w(TAG, "Notification permission denied - VPN status won't show in notifications")
        }
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
        tvStatusPrev = view.findViewById(R.id.tv_status_prev)  // Teleprompter previous status
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
        
        // Observe SharedConnectionViewModel for instant state sync
        observeConnectionViewModel()

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
     * REQUIREMENT #1: Broadcasts updates for real-time UI sync
     * 
     * SMART REFRESH: Respects 60-second cooldown to prevent battery/data drain
     * from frequent screen navigation (Home → ServerList → Home)
     */
    private fun refreshPingsInBackground() {
        // SMART REFRESH: Check cooldown to prevent ping spam
        val now = System.currentTimeMillis()
        val timeSinceLastPing = now - lastPingTimestamp
        
        if (timeSinceLastPing < PING_COOLDOWN_MS) {
            val remainingSeconds = (PING_COOLDOWN_MS - timeSinceLastPing) / 1000
            Log.d(TAG, "Skipping ping (Cooldown active: ${remainingSeconds}s remaining)")
            return
        }
        
        val cachedServers = kittoku.osc.repository.ServerCache.loadCachedServers(prefs)
        if (cachedServers.isNullOrEmpty()) {
            Log.d(TAG, "No cached servers to refresh pings for")
            return
        }
        
        // Update timestamp BEFORE starting ping (prevents re-entry)
        lastPingTimestamp = now
        Log.d(TAG, "Background ping refresh: ${cachedServers.size} servers")
        
        // Refresh pings in background with LIVE UI updates
        vpnRepository.measureRealPingsParallel(
            cachedServers,
            onServerUpdated = { _, updatedServer ->
                // REQUIREMENT #1: Broadcast each server update for real-time UI
                try {
                    context?.let { ctx ->
                        kittoku.osc.repository.PingUpdateManager.notifyServerPingUpdate(ctx, updatedServer)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to broadcast ping update: ${e.message}")
                }
            },
            onProgress = { current, total ->
                // Broadcast progress for UI
                try {
                    context?.let { ctx ->
                        kittoku.osc.repository.PingUpdateManager.notifyProgress(ctx, current, total)
                    }
                } catch (e: Exception) { /* ignore */ }
            },
            onComplete = { sortedServers ->
                // Filter out dead servers and save
                val liveServers = sortedServers.filter { it.realPing > 0 }
                if (liveServers.isNotEmpty()) {
                    kittoku.osc.repository.ServerCache.saveFilteredServersWithPings(prefs, liveServers)
                    Log.d(TAG, "Background ping complete: ${liveServers.size} live servers cached")
                    
                    // REQUIREMENT #1: Broadcast completion for final UI sort
                    try {
                        context?.let { ctx ->
                            kittoku.osc.repository.PingUpdateManager.notifyPingComplete(ctx, liveServers)
                        }
                    } catch (e: Exception) { /* ignore */ }
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
     * Observe SharedConnectionViewModel for instant sync with ManualConnectFragment
     * When user connects from ManualFragment, this updates our UI too
     */
    private fun observeConnectionViewModel() {
        connectionViewModel.connectionState.observe(viewLifecycleOwner) { state ->
            Log.d(TAG, "ViewModel state changed: $state")
            
            // Sync local state with ViewModel
            when (state) {
                SharedConnectionViewModel.ConnectionState.DISCONNECTED -> {
                    if (currentState != ConnectionState.DISCONNECTED) {
                        currentState = ConnectionState.DISCONNECTED
                        updateStatusUI("DISCONNECTED")
                    }
                }
                SharedConnectionViewModel.ConnectionState.CONNECTING -> {
                    if (currentState != ConnectionState.CONNECTING) {
                        currentState = ConnectionState.CONNECTING
                        updateStatusUI("Connecting...")
                    }
                }
                SharedConnectionViewModel.ConnectionState.CONNECTED -> {
                    if (currentState != ConnectionState.CONNECTED) {
                        currentState = ConnectionState.CONNECTED
                        updateStatusUI("CONNECTED")
                        updateServerInfoDisplay()
                        fetchRealConnectionInfo()
                    }
                }
                SharedConnectionViewModel.ConnectionState.DISCONNECTING -> {
                    // Show disconnecting in UI
                    updateStatusUI("Disconnecting...")
                }
            }
        }
        
        connectionViewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                connectionViewModel.clearError()
            }
        }
    }

    /**
     * REFACTORED SMART CONNECT LOGIC
     * 
     * NEW Priority Order (Task 3):
     * 1. Top 3 Best Quality Score servers (from QoS algorithm)
     * 2. Last Successful Server (only if top 3 fail or timeout)
     * 3. Cold Start - fetch and ping all servers
     * 
     * Rationale: "Last Successful" often degrades over time, so prioritize
     * servers that currently score best on Effective Speed + Ping
     */
    private fun loadServersAndConnect() {
        try {
            // Check network connectivity FIRST
            if (!isNetworkAvailable()) {
                Log.w(TAG, "No network connection available")
                updateStatusUI("No Internet")
                Toast.makeText(context, "No internet connection. Please check your network.", Toast.LENGTH_LONG).show()
                currentState = ConnectionState.DISCONNECTED
                return
            }
            
            // ========================================
            // JUST-IN-TIME SORTING (Task 1)
            // Re-calculate & Re-sort RIGHT before selection
            // Formula: QualityScore = 0.6*(Speed/(Sessions+1)) + 0.4*(PingScore)
            // ========================================
            
            // Step 1: Load current data from cache or ViewModel
            val cachedServers = kittoku.osc.repository.ServerCache.loadSortedServersWithPings(prefs)
            val viewModelServers = serverViewModel.servers.value
            
            // Use whichever source has data
            val dataSource = when {
                !viewModelServers.isNullOrEmpty() -> viewModelServers
                !cachedServers.isNullOrEmpty() -> cachedServers
                else -> null
            }
            
            if (dataSource != null && dataSource.isNotEmpty()) {
                // Step 2: FRESH SORT - Re-calculate Quality Scores RIGHT NOW
                // This ensures we don't use a stale pre-sorted list
                Log.d(TAG, "JUST-IN-TIME: Re-sorting ${dataSource.size} servers before connection")
                val freshSorted = ServerSorter.sortByScore(dataSource)  // Fresh calculation
                
                if (freshSorted.isNotEmpty()) {
                    // Step 3: Pick absolute best server (Index 0)
                    val bestServer = freshSorted.first()
                    val topServers = freshSorted.take(3)
                    
                    Log.d(TAG, "Best Server: ${bestServer.hostName}")
                    Log.d(TAG, "Top 3: ${topServers.map { 
                        "${it.hostName} (Score=${String.format("%.3f", ServerSorter.calculateScore(it)/1_000_000)})" 
                    }}")
                    
                    updateStatusUI("Connecting to best server...")
                    connectionViewModel.setConnecting(bestServer.hostName, isManual = false)
                    
                    // Set up failover list (already sorted by quality)
                    servers.clear()
                    servers.addAll(freshSorted)
                    currentServerIndex = 0
                    attemptedServers.clear()
                    isFailoverActive = true
                    isUserInitiatedDisconnect = false
                    
                    connectToServer(bestServer.hostName)
                    return
                }
            }
            
            // PRIORITY 2: Fallback to Last Successful Server (if no cached servers)
            val lastSuccessful: String? = vpnRepository.getLastSuccessfulServer()
            if (!lastSuccessful.isNullOrBlank()) {
                Log.d(TAG, "Priority 2 (Fallback): Connecting to last successful: $lastSuccessful")
                updateStatusUI("Reconnecting...")
                
                // Set up failover with just this server
                isFailoverActive = true
                isUserInitiatedDisconnect = false
                attemptedServers.clear()
                
                connectToServer(lastSuccessful)
                return
            }
            
            // PRIORITY 3: Cold start - fetch and ping all servers
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
        vpnRepository.fetchSstpServers(prefs) { newServers ->
            activity?.runOnUiThread {
                try {
                    if (newServers.isEmpty()) {
                        updateStatusUI("No servers available")
                        currentState = ConnectionState.DISCONNECTED
                        return@runOnUiThread
                    }
                    
                    // NOTE: Servers already saved atomically by fetchSstpServers
                    
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
            
            // Reload servers from cache if list is empty
            if (servers.isEmpty()) {
                val cachedServers = kittoku.osc.repository.ServerCache.loadSortedServersWithPings(prefs)
                    ?: kittoku.osc.repository.ServerCache.loadCachedServers(prefs)
                if (cachedServers != null && cachedServers.isNotEmpty()) {
                    servers.clear()
                    servers.addAll(cachedServers)
                    Log.d(TAG, "Reloaded ${cachedServers.size} servers from cache for retry")
                } else {
                    Log.e(TAG, "Cannot retry: no servers in cache")
                    updateStatusUI("No servers available")
                    currentState = ConnectionState.DISCONNECTED
                    return
                }
            }
            
            // Re-ping to get latest network status
            val serversToTest = servers.take(10)
            Log.d(TAG, "Testing ${serversToTest.size} servers")
            
            vpnRepository.rapidPingServers(serversToTest) { freshPingedServers ->
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
                        
                        // Find best server not already attempted AND not failed this session
                        val nextBest = sortedServers.firstOrNull { 
                            !attemptedServers.contains(it.hostName) && 
                            !failedServersThisSession.contains(it.hostName) 
                        }
                        
                        if (nextBest != null) {
                            Log.d(TAG, "SMART RETRY: Next best server: ${nextBest.hostName} (${nextBest.realPing}ms)")
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
            vpnRepository.fetchSstpServers(prefs) { newServers ->
                activity?.runOnUiThread {
                    if (newServers.isNotEmpty()) {
                        // NOTE: Servers already saved atomically by fetchSstpServers
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
        // SMART RETRY: Find next server that hasn't been attempted AND hasn't failed this session
        while (currentServerIndex < servers.size) {
            val server = servers[currentServerIndex]
            if (!attemptedServers.contains(server.hostName) && 
                !failedServersThisSession.contains(server.hostName)) {
                break
            }
            currentServerIndex++
        }
        
        if (currentServerIndex >= servers.size) {
            updateStatusUI("All servers failed")
            isFailoverActive = false
            attemptedServers.clear()
            // Clear failed servers for next connection attempt
            failedServersThisSession.clear()
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
        
        // TELEPROMPTER EFFECT: Show previous status fading above
        if (currentStatusText.isNotEmpty() && currentStatusText != status) {
            // Move current status to previous (faded)
            tvStatusPrev.text = currentStatusText
            tvStatusPrev.visibility = View.VISIBLE
            
            // Animate previous status: fade out and slide up
            tvStatusPrev.animate()
                .alpha(0f)
                .translationY(-20f)
                .setDuration(400)
                .withEndAction {
                    if (isAdded) {
                        tvStatusPrev.visibility = View.GONE
                        tvStatusPrev.translationY = 0f
                        tvStatusPrev.alpha = 0.4f
                    }
                }
                .start()
            
            // Animate main status: fade in from below
            tvStatus.alpha = 0f
            tvStatus.translationY = 15f
            tvStatus.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(350)
                .start()
        }
        
        currentStatusText = status
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
            status.startsWith("Loading", ignoreCase = true) ||
            status.startsWith("Server unreachable", ignoreCase = true) ||
            status.startsWith("Retry", ignoreCase = true) -> {
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
