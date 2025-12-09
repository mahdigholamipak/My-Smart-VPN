package kittoku.osc.repository

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Data class representing an SSTP-compatible VPN server
 */
data class SstpServer(
    val hostName: String,
    val ip: String,
    val country: String,
    val countryCode: String,
    val speed: Long,
    val sessions: Long,
    val ping: Int,
    val score: Long = 0  // Score from CSV index 2 for smart failover
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
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch SSTP servers from the VPNGate mirror CSV
     * @param onResult Callback with the list of parsed servers sorted by score (highest first)
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
     * Uses TCP socket connection time which is reliable and doesn't require root
     * 
     * @param hostname The hostname to check
     * @param port Port to connect to (default 443 for SSTP)
     * @return Latency in milliseconds, or -1 if unreachable
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
     * Parse CSV data into list of SstpServer objects
     * 
     * Applies the following rules:
     * 1. Hostname Fix: Append ".opengw.net" if not already present
     * 2. Data Types: Parse Speed (Index 4), Sessions (Index 7), Score (Index 2) as Long
     * 3. Filters: Exclude CountryCode "IR"
     * 4. Sorting: Sort by Score (Descending) for smart failover
     */
    private fun parseCsv(data: String): List<SstpServer> {
        val servers = mutableListOf<SstpServer>()
        val lines = data.lines().filter { it.isNotBlank() }
        
        Log.d(TAG, "Processing ${lines.size} lines")
        
        for ((index, line) in lines.withIndex()) {
            // Skip metadata and header lines
            if (line.startsWith("*") || 
                line.startsWith("#") || 
                line.contains("HostName", ignoreCase = true)) {
                continue
            }
            
            try {
                val server = parseServerLine(line)
                if (server != null) {
                    servers.add(server)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing line $index: ${e.message}")
            }
        }
        
        Log.d(TAG, "Parsed ${servers.size} valid servers before sorting")
        
        // Sort by Score (Descending) for smart failover - highest score = most reliable
        return servers.sortedByDescending { it.score }
    }
    
    /**
     * Parse a single CSV line into an SstpServer object
     * @return SstpServer if valid, null if should be filtered out
     */
    private fun parseServerLine(line: String): SstpServer? {
        val parts = line.split(",")
        
        if (parts.size < 8) {
            return null
        }
        
        // Parse CountryCode (Index 6) - filter out excluded countries
        val countryCode = parts.getOrNull(6)?.trim().orEmpty()
        if (countryCode.equals(EXCLUDED_COUNTRY_CODE, ignoreCase = true)) {
            return null
        }
        
        // Parse Hostname (Index 0) with CRITICAL fix
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
        
        // Parse Score (Index 2) as Long - used for smart failover sorting
        val score = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
        
        val ping = parts.getOrNull(3)?.trim()?.toIntOrNull() ?: 0
        val speed = parts.getOrNull(4)?.trim()?.toLongOrNull() ?: 0L
        val country = parts.getOrNull(5)?.trim().orEmpty()
        val sessions = parts.getOrNull(7)?.trim()?.toLongOrNull() ?: 0L
        
        return SstpServer(
            hostName = hostName,
            ip = ip,
            country = country,
            countryCode = countryCode,
            speed = speed,
            sessions = sessions,
            ping = ping,
            score = score
        )
    }
}
