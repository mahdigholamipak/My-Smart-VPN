package kittoku.osc.repository

import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Data class representing an SSTP-compatible VPN server with smart scoring
 */
data class SstpServer(
    val hostName: String,
    val ip: String,
    val country: String,
    val countryCode: String,
    val speed: Long,
    val sessions: Long,
    val ping: Int,
    val score: Long = 0,
    val uptime: Long = 0,
    val totalTraffic: Long = 0,
    val smartRank: Double = 0.0,
    val isPublicVpn: Boolean = false,  // Flag to identify potentially problematic public VPN servers
    val realPing: Long = -1L           // Real-time measured ping (-1 = not measured)
)

/**
 * Repository for fetching VPN server list from VPNGate mirror
 * 
 * ISSUE #1 FIX: Improved scoring algorithm that:
 * - Penalizes "public-vpn-*" servers (they often reject connections)
 * - Prioritizes servers with moderate session counts (not too high, not too low)
 * - Gives bonus to previously successful servers
 * 
 * ISSUE #6 FIX: Increased timeout from 5s to 15s
 * 
 * ISSUE #7 FIX: Removed IR country filter - all servers are now included
 */
class VpnRepository {
    companion object {
        private const val TAG = "VpnRepository"
        private const val SERVER_URL = "https://gist.githubusercontent.com/mahdigholamipak/32b54c505f61fcdb34ddf3a239a29349/raw/server_list.csv"
        private const val OPENGW_SUFFIX = ".opengw.net"
        
        // Strict 999ms timeout for quick reachability check
        private const val LATENCY_CHECK_PORT = 443
        private const val LATENCY_TIMEOUT_MS = 999
        
        // Parallel ping configuration
        private const val PARALLEL_PING_BATCH_SIZE = 20
        
        // NOTE: QoS scoring moved to ServerSorter.kt
        // Kept only for legacy parseCsv smartRank calculation
        private const val PUBLIC_VPN_PENALTY = -100.0
        private const val SUCCESS_BONUS = 200.0
        
        // Prefs keys
        private const val PREF_SUCCESS_SERVERS = "success_servers"
        private const val PREF_LAST_SUCCESSFUL = "last_successful_server"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private var successfulServers = mutableSetOf<String>()
    private var lastSuccessfulServer: String? = null

    fun loadSuccessHistory(prefs: SharedPreferences) {
        val savedServers = prefs.getStringSet(PREF_SUCCESS_SERVERS, emptySet()) ?: emptySet()
        successfulServers.clear()
        successfulServers.addAll(savedServers)
        lastSuccessfulServer = prefs.getString(PREF_LAST_SUCCESSFUL, null)
        Log.d(TAG, "Loaded ${successfulServers.size} successful servers, last: $lastSuccessfulServer")
    }
    
    /**
     * Get last successfully connected server hostname
     * Used for "Last Connected Server Priority" logic
     */
    fun getLastSuccessfulServer(): String? {
        return lastSuccessfulServer
    }
    
    fun markServerSuccess(prefs: SharedPreferences, hostname: String) {
        successfulServers.add(hostname)
        lastSuccessfulServer = hostname
        
        prefs.edit()
            .putStringSet(PREF_SUCCESS_SERVERS, successfulServers)
            .putString(PREF_LAST_SUCCESSFUL, hostname)
            .apply()
        
        Log.d(TAG, "Marked server as successful: $hostname")
    }

    /**
     * Fetch servers with ATOMIC cache persistence
     * 
     * @param prefs SharedPreferences for immediate cache save (null = don't cache)
     * @param onResult Callback with parsed server list
     */
    fun fetchSstpServers(prefs: SharedPreferences? = null, onResult: (List<SstpServer>) -> Unit) {
        val request = Request.Builder()
            .url(SERVER_URL)
            .build()

        Thread {
            try {
                Log.d(TAG, "Fetching server list from: ***SECURE_URL***")
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP error: ${response.code}")
                    onResult(emptyList())
                    return@Thread
                }
                
                val csvData = response.body?.string()
                if (csvData.isNullOrBlank()) {
                    Log.e(TAG, "Empty response body")
                    onResult(emptyList())
                    return@Thread
                }
                
                Log.d(TAG, "Received CSV data, length: ${csvData.length}")
                val servers = parseCsv(csvData)
                Log.d(TAG, "Parsed ${servers.size} servers")
                
                // ATOMIC CACHE: Save immediately after successful parse
                // This ensures HomeFragment sees fresh data even if user navigates away
                if (prefs != null && servers.isNotEmpty()) {
                    ServerCache.saveServers(prefs, servers)
                    Log.d(TAG, "Inserted ${servers.size} servers into cache")
                }
                
                onResult(servers)
                
            } catch (e: IOException) {
                Log.e(TAG, "Network error fetching servers", e)
                onResult(emptyList())
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error fetching servers", e)
                onResult(emptyList())
            }
        }.start()
    }
    
    /**
     * Measure latency with strict 999ms timeout
     * Uses minimal socket connection for quick reachability check
     */
    fun measureLatency(hostname: String, port: Int = LATENCY_CHECK_PORT): Long {
        return try {
            val startTime = System.currentTimeMillis()
            Socket().use { socket ->
                socket.soTimeout = LATENCY_TIMEOUT_MS
                socket.connect(InetSocketAddress(hostname, port), LATENCY_TIMEOUT_MS)
                val endTime = System.currentTimeMillis()
                endTime - startTime
            }
        } catch (e: Exception) {
            // Don't log every timeout - too noisy
            -1L
        }
    }
    
    fun measureLatencyAsync(hostname: String, onResult: (Long) -> Unit) {
        Thread {
            val latency = measureLatency(hostname)
            onResult(latency)
        }.start()
    }
    
    /**
     * CRITICAL FIX: Parallel ping measurement with live per-server updates
     * 
     * - Uses Kotlin Coroutines for parallel execution
     * - 999ms strict timeout per server
     * - Updates UI immediately as each server ping completes
     * - Does NOT block UI waiting for all pings
     * 
     * @param servers List of servers to measure
     * @param onServerUpdated Called immediately when a single server's ping is measured (for live UI update)
     * @param onProgress Called with progress count
     * @param onComplete Called when ALL pings are done with sorted list
     */
    fun measureRealPingsParallel(
        servers: List<SstpServer>,
        onServerUpdated: (Int, SstpServer) -> Unit,  // (index, updatedServer)
        onProgress: (Int, Int) -> Unit,
        onComplete: (List<SstpServer>) -> Unit
    ) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        scope.launch {
            Log.d(TAG, "Starting PARALLEL ping measurement for ${servers.size} servers")
            val startTime = System.currentTimeMillis()
            
            // Thread-safe storage for results
            val results = ConcurrentHashMap<Int, SstpServer>()
            val completedCount = AtomicInteger(0)
            val total = servers.size
            
            // Launch all pings in parallel with controlled concurrency
            val jobs = servers.mapIndexed { index, server ->
                async {
                    val realPing = measureLatency(server.hostName)
                    val updatedServer = server.copy(realPing = realPing)
                    results[index] = updatedServer
                    
                    val completed = completedCount.incrementAndGet()
                    
                    // Notify UI immediately for this server
                    withContext(Dispatchers.Main) {
                        onServerUpdated(index, updatedServer)
                        onProgress(completed, total)
                    }
                    
                    updatedServer
                }
            }
            
            // Wait for all to complete
            jobs.awaitAll()
            
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "Parallel ping complete in ${elapsed}ms for ${servers.size} servers")
            
            // Build final sorted list using ServerSorter (60% effective speed, 40% ping)
            val serverList = results.values.toList()
            val sortedServers = ServerSorter.sortByScore(serverList)
            
            withContext(Dispatchers.Main) {
                onComplete(sortedServers)
            }
        }
    }
    
    // NOTE: QoS scoring methods moved to ServerSorter.kt
    // Use ServerSorter.sortByScore(), ServerSorter.getBestServer(), etc.
    
    /**
     * Legacy method for backward compatibility
     */
    fun measureRealPingsAsync(
        servers: List<SstpServer>,
        onProgress: (Int, Int) -> Unit,
        onComplete: (List<SstpServer>) -> Unit
    ) {
        measureRealPingsParallel(
            servers,
            onServerUpdated = { _, _ -> },  // No live updates in legacy mode
            onProgress = onProgress,
            onComplete = onComplete
        )
    }
    
    /**
     * ISSUE #1 FIX: Rapid ping for cold start
     * Pings a small number of servers quickly (max 1 second total)
     * Used when no cached pings exist to find the best server fast
     */
    fun rapidPingServers(
        servers: List<SstpServer>,
        onComplete: (List<SstpServer>) -> Unit
    ) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        scope.launch {
            Log.d(TAG, "Rapid ping: Testing ${servers.size} servers")
            val startTime = System.currentTimeMillis()
            
            // Quick timeout: 800ms per server, parallel execution
            val results = servers.map { server ->
                async {
                    try {
                        withTimeout(800L) {
                            val ping = measureLatency(server.hostName)
                            server.copy(realPing = ping)
                        }
                    } catch (e: Exception) {
                        server.copy(realPing = -1L)
                    }
                }
            }.awaitAll()
            
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "Rapid ping complete in ${elapsed}ms")
            
            withContext(Dispatchers.Main) {
                onComplete(results)
            }
        }
    }

    // NOTE: QoS scoring moved to ServerSorter.kt
    // parseCsv uses simple preliminary rank, real QoS applied after ping

    private fun parseCsv(data: String): List<SstpServer> {
        val rawServers = mutableListOf<RawServerData>()
        val lines = data.lines().filter { it.isNotBlank() }
        
        Log.d(TAG, "Processing ${lines.size} lines")
        
        for ((index, line) in lines.withIndex()) {
            // Skip metadata and header lines only
            if (line.startsWith("*") || line.startsWith("#") || 
                line.contains("HostName", ignoreCase = true)) {
                continue
            }
            
            try {
                val raw = parseRawServerLine(line)
                if (raw != null) {
                    rawServers.add(raw)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing line $index: ${e.message}")
            }
        }
        
        // Create servers with simple preliminary rank (real QoS applied by ServerSorter after ping)
        val servers = rawServers.map { raw ->
            // Simple preliminary rank: speed / sessions, with bonuses
            var simpleRank = raw.speed.toDouble() / (raw.sessions + 1).toDouble()
            
            // Penalty for public-vpn servers
            if (raw.isPublicVpn) {
                simpleRank += PUBLIC_VPN_PENALTY
            }
            
            // Bonus for previously successful servers
            if (successfulServers.contains(raw.hostName)) {
                simpleRank += SUCCESS_BONUS
            }
            
            SstpServer(
                hostName = raw.hostName,
                ip = raw.ip,
                country = raw.country,
                countryCode = raw.countryCode,
                speed = raw.speed,
                sessions = raw.sessions,
                ping = 0,              // Not in CSV, will measure realPing locally
                score = 0L,            // Not in CSV, calculated as QoS score
                uptime = 0L,           // Not in CSV, unused
                totalTraffic = 0L,     // Not in CSV, unused
                smartRank = simpleRank,
                isPublicVpn = raw.isPublicVpn
            )
        }
        
        Log.d(TAG, "Parsed ${servers.size} servers")
        
        // Sort by preliminary rank (will be re-sorted by ServerSorter after ping)
        return servers.sortedWith(
            compareByDescending<SstpServer> { it.hostName == lastSuccessfulServer }
                .thenByDescending { it.smartRank }
        )
    }
    
    /**
     * Internal data class for CSV parsing
     * Optimized 6-column schema: HostName, IP, Speed, CountryLong, CountryShort, Sessions
     */
    private data class RawServerData(
        val hostName: String,
        val ip: String,
        val country: String,
        val countryCode: String,
        val speed: Long,
        val sessions: Long,
        val isPublicVpn: Boolean
    )
    
    /**
     * Parse a single CSV line with OPTIMIZED 6-column schema:
     * Index 0: HostName
     * Index 1: IP
     * Index 2: Speed
     * Index 3: CountryLong
     * Index 4: CountryShort
     * Index 5: NumVpnSessions
     * 
     * TOXIC SERVER FIX: Added hostname/IP validation to filter malformed entries
     */
    private fun parseRawServerLine(line: String): RawServerData? {
        try {
            val parts = line.split(",")
            
            // Require exactly 6 columns in new schema
            if (parts.size < 6) {
                return null
            }
            
            var hostName = parts[0].trim()
            if (hostName.isEmpty()) {
                return null
            }
            
            // TOXIC SERVER FIX: Validate hostname format
            if (!isValidHostname(hostName)) {
                Log.d(TAG, "Skipping invalid hostname: $hostName")
                return null
            }
            
            // Detect public-vpn-* servers (these often fail)
            val isPublicVpn = hostName.startsWith("public-vpn-", ignoreCase = true)
            
            if (!hostName.endsWith(OPENGW_SUFFIX, ignoreCase = true)) {
                hostName += OPENGW_SUFFIX
            }
            
            val ip = parts.getOrNull(1)?.trim().orEmpty()
            if (ip.isEmpty() || !isValidIpAddress(ip)) {
                return null
            }
            
            // NEW 6-column schema indices
            val speed = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
            val country = parts.getOrNull(3)?.trim().orEmpty()
            val countryCode = parts.getOrNull(4)?.trim().orEmpty()
            val sessions = parts.getOrNull(5)?.trim()?.toLongOrNull() ?: 0L
            
            return RawServerData(
                hostName = hostName,
                ip = ip,
                country = country,
                countryCode = countryCode,
                speed = speed,
                sessions = sessions,
                isPublicVpn = isPublicVpn
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server line: ${e.message}")
            return null
        }
    }
    
    /** Validate hostname format */
    private fun isValidHostname(hostname: String): Boolean {
        if (hostname.length < 3) return false
        if (hostname.contains(" ") || hostname.contains("\"")) return false
        if (!hostname.any { it.isLetterOrDigit() }) return false
        return hostname.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }
    
    /** Basic IP address validation */
    private fun isValidIpAddress(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val num = part.toIntOrNull()
            num != null && num in 0..255
        }
    }
}
