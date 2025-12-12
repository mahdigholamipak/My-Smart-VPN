package kittoku.osc.repository

import android.util.Log

/**
 * ServerSorter - Implements QoS-based server scoring and filtering
 * 
 * Algorithm: Score = Speed / (Sessions + 1)
 * 
 * The "+1" accounts for the user's own potential connection,
 * prevents division by zero, and simulates load after connection.
 * 
 * Timeout Handling:
 * - Servers with Timeout/Unreachable ping are scored as -1
 * - These servers are DISCARDED and not cached
 */
object ServerSorter {
    private const val TAG = "ServerSorter"
    
    /**
     * Calculate Quality Score for a server
     * Formula: Score = Speed / (Sessions + 1)
     * 
     * @param server The server to score
     * @return Score value, or -1.0 if server should be discarded
     */
    fun calculateScore(server: SstpServer): Double {
        // CRITICAL: If ping timed out or unreachable, discard this server
        if (server.realPing <= 0) {
            Log.d(TAG, "Discarding ${server.hostName}: Ping timeout/unreachable")
            return -1.0
        }
        
        // Score = Speed / (Sessions + 1)
        // +1 prevents division by zero and simulates our connection
        val sessions = server.sessions + 1
        val score = server.speed.toDouble() / sessions.toDouble()
        
        Log.d(TAG, "Score for ${server.hostName}: " +
                "speed=${server.speed/1_000_000}Mbps / (sessions=${server.sessions}+1) = ${String.format("%.2f", score/1_000_000)}")
        
        return score
    }
    
    /**
     * Sort servers by Quality Score (highest first)
     * Also filters out servers with timeout/unreachable pings
     * 
     * @param servers List of servers to sort
     * @return Sorted list (best servers first), timeout servers excluded
     */
    fun sortByScore(servers: List<SstpServer>): List<SstpServer> {
        if (servers.isEmpty()) return emptyList()
        
        // Calculate scores and filter out timeouts (score = -1)
        val scoredServers = servers
            .map { it to calculateScore(it) }
            .filter { it.second > 0 }  // Discard timeout servers
            .sortedByDescending { it.second }
            .map { it.first }
        
        Log.d(TAG, "Sorted ${scoredServers.size} servers (${servers.size - scoredServers.size} discarded as timeout)")
        
        return scoredServers
    }
    
    /**
     * Get top N servers by score
     * Timeout servers are already filtered out
     * 
     * @param servers List to process
     * @param count Number of top servers to return
     * @return Top N servers sorted by score
     */
    fun getTopServers(servers: List<SstpServer>, count: Int = 3): List<SstpServer> {
        return sortByScore(servers).take(count)
    }
    
    /**
     * Get best server (highest score)
     * Returns null if all servers timed out
     */
    fun getBestServer(servers: List<SstpServer>): SstpServer? {
        return sortByScore(servers).firstOrNull()
    }
    
    /**
     * Filter servers - removes all timeout/unreachable servers
     * Use this before caching to ensure only reachable servers are stored
     * 
     * @param servers List to filter
     * @return Only servers with positive ping (reachable)
     */
    fun filterReachable(servers: List<SstpServer>): List<SstpServer> {
        val reachable = servers.filter { it.realPing > 0 }
        Log.d(TAG, "Filtered: ${reachable.size} reachable / ${servers.size} total")
        return reachable
    }
}
