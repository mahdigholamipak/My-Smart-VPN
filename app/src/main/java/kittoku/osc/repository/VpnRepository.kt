package kittoku.osc.repository

import android.content.SharedPreferences
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

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
        private const val SERVER_URL = "https://raw.githubusercontent.com/mahdigholamipak/vpn-list-mirror/refs/heads/main/server_list.csv"
        private const val OPENGW_SUFFIX = ".opengw.net"
        
        // ISSUE #6 FIX: Increased timeout from 5000ms to 15000ms
        private const val LATENCY_CHECK_PORT = 443
        private const val LATENCY_TIMEOUT_MS = 15000
        
        // Smart Scoring Weights - ISSUE #1 FIX: Revised algorithm
        private const val WEIGHT_SPEED = 0.25
        private const val WEIGHT_UPTIME = 0.35          // Higher weight for stable servers
        private const val WEIGHT_PING = -0.15           // Lower ping = better
        private const val WEIGHT_SESSIONS_OPTIMAL = 0.10 // Moderate sessions = good (not too busy, not dead)
        private const val PUBLIC_VPN_PENALTY = -100.0    // Strong penalty for "public-vpn-*" servers
        private const val SUCCESS_BONUS = 200.0          // Bonus for previously successful servers
        
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
    
    fun markServerSuccess(prefs: SharedPreferences, hostname: String) {
        successfulServers.add(hostname)
        lastSuccessfulServer = hostname
        
        prefs.edit()
            .putStringSet(PREF_SUCCESS_SERVERS, successfulServers)
            .putString(PREF_LAST_SUCCESSFUL, hostname)
            .apply()
        
        Log.d(TAG, "Marked server as successful: $hostname")
    }
    
    fun getLastSuccessfulServer(): String? = lastSuccessfulServer

    fun fetchSstpServers(onResult: (List<SstpServer>) -> Unit) {
        val request = Request.Builder()
            .url(SERVER_URL)
            .build()

        Thread {
            try {
                Log.d(TAG, "Fetching server list from: $SERVER_URL")
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
    
    fun measureLatency(hostname: String, port: Int = LATENCY_CHECK_PORT): Long {
        return try {
            val startTime = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(hostname, port), LATENCY_TIMEOUT_MS)
                val endTime = System.currentTimeMillis()
                endTime - startTime
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to measure latency for $hostname: ${e.message}")
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
     * Measure real ping for all servers asynchronously with progress updates
     * Called when server list fragment opens or refreshes
     * 
     * @param servers List of servers to measure
     * @param onProgress Callback with (current, total) count
     * @param onComplete Callback with sorted list of servers (sorted by real ping)
     */
    fun measureRealPingsAsync(
        servers: List<SstpServer>,
        onProgress: (Int, Int) -> Unit,
        onComplete: (List<SstpServer>) -> Unit
    ) {
        Thread {
            Log.d(TAG, "Starting real ping measurement for ${servers.size} servers")
            
            val updatedServers = servers.mapIndexed { index, server ->
                val realPing = measureLatency(server.hostName)
                onProgress(index + 1, servers.size)
                server.copy(realPing = realPing)
            }
            
            // Sort by realPing (lowest first), connected server always first
            // Servers with failed ping (-1) go to the end
            val sorted = updatedServers.sortedWith(
                compareByDescending<SstpServer> { it.hostName == lastSuccessfulServer }
                    .thenBy { if (it.realPing < 0) Long.MAX_VALUE else it.realPing }
            )
            
            Log.d(TAG, "Ping measurement complete. Sorted ${sorted.size} servers by latency")
            onComplete(sorted)
        }.start()
    }

    /**
     * ISSUE #1 FIX: Revised smart ranking algorithm
     * 
     * Key observations from user data:
     * - public-vpn-* servers with high raw scores (2.9M) FAIL
     * - Private servers with lower scores (1.1M) SUCCEED
     * - High session counts alone don't indicate failure
     * 
     * New algorithm prioritizes:
     * 1. Previously successful servers (massive bonus)
     * 2. Non-public-vpn servers (public ones get penalty)
     * 3. Good uptime (stability indicator)
     * 4. Moderate session count (10-100 is optimal)
     * 5. Lower ping
     */
    private fun calculateSmartRank(
        speed: Long,
        uptime: Long,
        sessions: Long,
        ping: Int,
        hostname: String,
        isPublicVpn: Boolean,
        maxSpeed: Long,
        maxUptime: Long,
        maxPing: Int
    ): Double {
        // Normalize to 0-100 scale
        val normalizedSpeed = if (maxSpeed > 0) (speed.toDouble() / maxSpeed) * 100 else 0.0
        val normalizedUptime = if (maxUptime > 0) (uptime.toDouble() / maxUptime) * 100 else 0.0
        val normalizedPing = if (maxPing > 0) (ping.toDouble() / maxPing) * 100 else 0.0
        
        // Session scoring: moderate is best (10-100 sessions)
        val sessionScore = when {
            sessions < 5 -> 20.0      // Too few = possibly dead/unreliable
            sessions in 5..50 -> 100.0  // Sweet spot
            sessions in 51..150 -> 70.0 // Moderate load
            else -> 40.0              // High load
        }
        
        // Calculate base rank
        var rank = (normalizedSpeed * WEIGHT_SPEED) +
                   (normalizedUptime * WEIGHT_UPTIME) +
                   (normalizedPing * WEIGHT_PING) +
                   (sessionScore * WEIGHT_SESSIONS_OPTIMAL)
        
        // ISSUE #1 FIX: Heavy penalty for public-vpn-* servers
        if (isPublicVpn) {
            rank += PUBLIC_VPN_PENALTY
            Log.d(TAG, "Applied public-vpn penalty to $hostname")
        }
        
        // Bonus for previously successful servers
        if (successfulServers.contains(hostname)) {
            rank += SUCCESS_BONUS
            Log.d(TAG, "Applied success bonus to $hostname")
        }
        
        return rank
    }

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
        
        // Find max values for normalization
        val maxSpeed = rawServers.maxOfOrNull { it.speed } ?: 1L
        val maxUptime = rawServers.maxOfOrNull { it.uptime } ?: 1L
        val maxPing = rawServers.maxOfOrNull { it.ping } ?: 1
        
        // Calculate smart ranks
        val servers = rawServers.map { raw ->
            val smartRank = calculateSmartRank(
                raw.speed, raw.uptime, raw.sessions, raw.ping, raw.hostName, raw.isPublicVpn,
                maxSpeed, maxUptime, maxPing
            )
            
            SstpServer(
                hostName = raw.hostName,
                ip = raw.ip,
                country = raw.country,
                countryCode = raw.countryCode,
                speed = raw.speed,
                sessions = raw.sessions,
                ping = raw.ping,
                score = raw.score,
                uptime = raw.uptime,
                totalTraffic = raw.totalTraffic,
                smartRank = smartRank,
                isPublicVpn = raw.isPublicVpn
            )
        }
        
        // ISSUE #7 FIX: No filters removed - all servers included
        Log.d(TAG, "Parsed ${servers.size} servers (no country filters)")
        
        // Sort: last successful first, then by smartRank (highest first)
        return servers.sortedWith(
            compareByDescending<SstpServer> { it.hostName == lastSuccessfulServer }
                .thenByDescending { it.smartRank }
        )
    }
    
    private data class RawServerData(
        val hostName: String,
        val ip: String,
        val country: String,
        val countryCode: String,
        val speed: Long,
        val sessions: Long,
        val ping: Int,
        val score: Long,
        val uptime: Long,
        val totalTraffic: Long,
        val isPublicVpn: Boolean
    )
    
    /**
     * ISSUE #7 FIX: Removed IR country filter - all servers are now parsed
     */
    private fun parseRawServerLine(line: String): RawServerData? {
        val parts = line.split(",")
        
        if (parts.size < 11) {
            return null
        }
        
        val countryCode = parts.getOrNull(6)?.trim().orEmpty()
        // ISSUE #7 FIX: Removed IR filter - no country is excluded now
        // All servers are included regardless of country
        
        var hostName = parts[0].trim()
        if (hostName.isEmpty()) {
            return null
        }
        
        // Detect public-vpn-* servers (these often fail)
        val isPublicVpn = hostName.startsWith("public-vpn-", ignoreCase = true)
        
        if (!hostName.endsWith(OPENGW_SUFFIX, ignoreCase = true)) {
            hostName += OPENGW_SUFFIX
        }
        
        val ip = parts.getOrNull(1)?.trim().orEmpty()
        if (ip.isEmpty()) {
            return null
        }
        
        val score = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
        val ping = parts.getOrNull(3)?.trim()?.toIntOrNull() ?: 0
        val speed = parts.getOrNull(4)?.trim()?.toLongOrNull() ?: 0L
        val country = parts.getOrNull(5)?.trim().orEmpty()
        val sessions = parts.getOrNull(7)?.trim()?.toLongOrNull() ?: 0L
        val uptime = parts.getOrNull(8)?.trim()?.toLongOrNull() ?: 0L
        val totalTraffic = parts.getOrNull(10)?.trim()?.toLongOrNull() ?: 0L
        
        return RawServerData(
            hostName = hostName,
            ip = ip,
            country = country,
            countryCode = countryCode,
            speed = speed,
            sessions = sessions,
            ping = ping,
            score = score,
            uptime = uptime,
            totalTraffic = totalTraffic,
            isPublicVpn = isPublicVpn
        )
    }
}
