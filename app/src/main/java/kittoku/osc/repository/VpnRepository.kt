package kittoku.osc.repository

import android.content.Context
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
    val smartRank: Double = 0.0  // Calculated weighted score
)

/**
 * Repository for fetching VPN server list from VPNGate mirror
 * 
 * CSV Format (15 columns, 0-indexed):
 * 0: HostName
 * 1: IP
 * 2: Score
 * 3: Ping
 * 4: Speed
 * 5: CountryLong
 * 6: CountryShort (CountryCode)
 * 7: NumVpnSessions
 * 8: Uptime
 * 9: TotalUsers
 * 10: TotalTraffic
 * 11: LogType
 * 12: Operator
 * 13: Message
 * 14: OpenVPN_ConfigData_Base64
 */
class VpnRepository {
    companion object {
        private const val TAG = "VpnRepository"
        private const val SERVER_URL = "https://raw.githubusercontent.com/mahdigholamipak/vpn-list-mirror/refs/heads/main/server_list.csv"
        private const val OPENGW_SUFFIX = ".opengw.net"
        private const val EXCLUDED_COUNTRY_CODE = "IR"
        private const val LATENCY_CHECK_PORT = 443
        private const val LATENCY_TIMEOUT_MS = 5000
        
        // Smart Scoring Weights
        private const val WEIGHT_SPEED = 0.35
        private const val WEIGHT_UPTIME = 0.30
        private const val WEIGHT_SESSIONS_PENALTY = -0.20  // Negative: penalize high sessions
        private const val WEIGHT_TRAFFIC = 0.15
        private const val SUCCESS_BONUS = 500.0  // Massive bonus for previously successful servers
        
        // Prefs keys for success tracking
        private const val PREF_SUCCESS_SERVERS = "success_servers"
        private const val PREF_LAST_SUCCESSFUL = "last_successful_server"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    // Set of server hostnames that connected successfully before
    private var successfulServers = mutableSetOf<String>()
    private var lastSuccessfulServer: String? = null

    /**
     * Load success history from SharedPreferences
     */
    fun loadSuccessHistory(prefs: SharedPreferences) {
        val savedServers = prefs.getStringSet(PREF_SUCCESS_SERVERS, emptySet()) ?: emptySet()
        successfulServers.clear()
        successfulServers.addAll(savedServers)
        lastSuccessfulServer = prefs.getString(PREF_LAST_SUCCESSFUL, null)
        Log.d(TAG, "Loaded ${successfulServers.size} successful servers, last: $lastSuccessfulServer")
    }
    
    /**
     * Mark a server as successfully connected
     */
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
     * Get the last successfully connected server
     */
    fun getLastSuccessfulServer(): String? = lastSuccessfulServer

    /**
     * Fetch SSTP servers with smart scoring
     * Priority: Last successful server first, then sorted by smartRank
     */
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
    
    /**
     * Measure TCP connection latency to a server
     */
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
    
    /**
     * Measure latency asynchronously
     */
    fun measureLatencyAsync(hostname: String, onResult: (Long) -> Unit) {
        Thread {
            val latency = measureLatency(hostname)
            onResult(latency)
        }.start()
    }

    /**
     * Calculate smart rank using weighted formula
     * 
     * Formula:
     * smartRank = (normalizedSpeed * WEIGHT_SPEED) 
     *           + (normalizedUptime * WEIGHT_UPTIME)
     *           + (normalizedSessions * WEIGHT_SESSIONS_PENALTY)  // Negative weight
     *           + (normalizedTraffic * WEIGHT_TRAFFIC)
     *           + (SUCCESS_BONUS if previously connected successfully)
     */
    private fun calculateSmartRank(
        speed: Long,
        uptime: Long,
        sessions: Long,
        totalTraffic: Long,
        hostname: String,
        maxSpeed: Long,
        maxUptime: Long,
        maxSessions: Long,
        maxTraffic: Long
    ): Double {
        // Normalize values to 0-100 scale
        val normalizedSpeed = if (maxSpeed > 0) (speed.toDouble() / maxSpeed) * 100 else 0.0
        val normalizedUptime = if (maxUptime > 0) (uptime.toDouble() / maxUptime) * 100 else 0.0
        val normalizedSessions = if (maxSessions > 0) (sessions.toDouble() / maxSessions) * 100 else 0.0
        val normalizedTraffic = if (maxTraffic > 0) (totalTraffic.toDouble() / maxTraffic) * 100 else 0.0
        
        // Calculate base rank
        var rank = (normalizedSpeed * WEIGHT_SPEED) +
                   (normalizedUptime * WEIGHT_UPTIME) +
                   (normalizedSessions * WEIGHT_SESSIONS_PENALTY) +  // Penalty for high sessions
                   (normalizedTraffic * WEIGHT_TRAFFIC)
        
        // Add massive bonus for previously successful servers
        if (successfulServers.contains(hostname)) {
            rank += SUCCESS_BONUS
            Log.d(TAG, "Applied success bonus to $hostname")
        }
        
        return rank
    }

    /**
     * Parse CSV data into list of SstpServer objects with smart ranking
     */
    private fun parseCsv(data: String): List<SstpServer> {
        val rawServers = mutableListOf<RawServerData>()
        val lines = data.lines().filter { it.isNotBlank() }
        
        Log.d(TAG, "Processing ${lines.size} lines")
        
        // First pass: parse all servers
        for ((index, line) in lines.withIndex()) {
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
        val maxSessions = rawServers.maxOfOrNull { it.sessions } ?: 1L
        val maxTraffic = rawServers.maxOfOrNull { it.totalTraffic } ?: 1L
        
        // Second pass: calculate smart ranks and create final objects
        val servers = rawServers.map { raw ->
            val smartRank = calculateSmartRank(
                raw.speed, raw.uptime, raw.sessions, raw.totalTraffic, raw.hostName,
                maxSpeed, maxUptime, maxSessions, maxTraffic
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
                smartRank = smartRank
            )
        }
        
        Log.d(TAG, "Parsed ${servers.size} valid servers")
        
        // Sort by smartRank, but put last successful server FIRST
        return servers.sortedWith(
            compareByDescending<SstpServer> { it.hostName == lastSuccessfulServer }
                .thenByDescending { it.smartRank }
        )
    }
    
    /**
     * Raw server data for first pass parsing
     */
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
        val totalTraffic: Long
    )
    
    /**
     * Parse a single CSV line into raw server data
     */
    private fun parseRawServerLine(line: String): RawServerData? {
        val parts = line.split(",")
        
        if (parts.size < 11) {
            return null
        }
        
        val countryCode = parts.getOrNull(6)?.trim().orEmpty()
        if (countryCode.equals(EXCLUDED_COUNTRY_CODE, ignoreCase = true)) {
            return null
        }
        
        var hostName = parts[0].trim()
        if (hostName.isEmpty()) {
            return null
        }
        
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
            totalTraffic = totalTraffic
        )
    }
}
