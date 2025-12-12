package kittoku.osc.repository

import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Server list caching for bandwidth optimization
 * 
 * Requirements:
 * - Cache server list locally
 * - Load local cache on launch
 * - Fetch remote CSV only if:
 *   1. User manually refreshes
 *   2. Local list is empty
 *   3. Cache is older than 4 hours AND connection is active
 */
object ServerCache {
    private const val TAG = "ServerCache"
    private const val PREF_CACHED_SERVERS = "cached_servers_json"
    private const val PREF_SORTED_SERVERS = "sorted_servers_with_pings"  // Issue #2: Persist pings
    private const val PREF_CACHE_TIMESTAMP = "cache_timestamp"
    private const val CACHE_VALIDITY_MS = 4 * 60 * 60 * 1000L  // 4 hours
    
    private val gson = Gson()
    
    /**
     * Save servers to local cache (raw from CSV)
     */
    fun saveServers(prefs: SharedPreferences, servers: List<SstpServer>) {
        try {
            val json = gson.toJson(servers)
            prefs.edit()
                .putString(PREF_CACHED_SERVERS, json)
                .putLong(PREF_CACHE_TIMESTAMP, System.currentTimeMillis())
                .apply()
            Log.d(TAG, "Cached ${servers.size} servers")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache servers", e)
        }
    }
    
    /**
     * ISSUE #2 FIX: Save servers WITH their realPing values (sorted)
     * Call this after ping measurement completes
     */
    fun saveSortedServersWithPings(prefs: SharedPreferences, servers: List<SstpServer>) {
        try {
            val json = gson.toJson(servers)
            prefs.edit()
                .putString(PREF_SORTED_SERVERS, json)
                .apply()
            Log.d(TAG, "Saved ${servers.size} servers with ping data")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save sorted servers", e)
        }
    }
    
    /**
     * ISSUE #3 FIX: Save servers with FILTERING of dead/timeout servers
     * Only servers with positive ping values (responding) are saved
     * Dead servers (ping = -1 or 0) are excluded to prevent wasting resources
     */
    fun saveFilteredServersWithPings(prefs: SharedPreferences, servers: List<SstpServer>) {
        try {
            // Filter out dead servers (timeout = -1, unmeasured = 0)
            val liveServers = servers.filter { it.realPing > 0 }
            val deadCount = servers.size - liveServers.size
            
            if (deadCount > 0) {
                Log.d(TAG, "Filtered out $deadCount dead/timeout servers")
            }
            
            val json = gson.toJson(liveServers)
            prefs.edit()
                .putString(PREF_SORTED_SERVERS, json)
                .apply()
            Log.d(TAG, "Saved ${liveServers.size} live servers with ping data")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save filtered servers", e)
        }
    }
    
    /**
     * ISSUE #2 FIX: Load sorted servers with persisted pings
     * Use this for initial load to show pre-sorted list from previous session
     */
    fun loadSortedServersWithPings(prefs: SharedPreferences): List<SstpServer>? {
        return try {
            val json = prefs.getString(PREF_SORTED_SERVERS, null)
            if (json.isNullOrBlank()) {
                Log.d(TAG, "No sorted servers found")
                return null
            }
            
            val type = object : TypeToken<List<SstpServer>>() {}.type
            val servers: List<SstpServer> = gson.fromJson(json, type)
            Log.d(TAG, "Loaded ${servers.size} sorted servers with pings")
            servers
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sorted servers", e)
            null
        }
    }
    
    /**
     * Load cached servers from local storage
     * @return List of cached servers, or null if cache is empty/invalid
     */
    fun loadCachedServers(prefs: SharedPreferences): List<SstpServer>? {
        return try {
            val json = prefs.getString(PREF_CACHED_SERVERS, null)
            if (json.isNullOrBlank()) {
                Log.d(TAG, "No cached servers found")
                return null
            }
            
            val type = object : TypeToken<List<SstpServer>>() {}.type
            val servers: List<SstpServer> = gson.fromJson(json, type)
            Log.d(TAG, "Loaded ${servers.size} servers from cache")
            servers
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached servers", e)
            null
        }
    }
    
    /**
     * Check if the cache is still valid (less than 4 hours old)
     */
    fun isCacheValid(prefs: SharedPreferences): Boolean {
        val timestamp = prefs.getLong(PREF_CACHE_TIMESTAMP, 0L)
        if (timestamp == 0L) return false
        
        val age = System.currentTimeMillis() - timestamp
        val isValid = age < CACHE_VALIDITY_MS
        Log.d(TAG, "Cache age: ${age / 1000 / 60} minutes, valid: $isValid")
        return isValid
    }
    
    /**
     * Get cache age in minutes (for display purposes)
     */
    fun getCacheAgeMinutes(prefs: SharedPreferences): Long {
        val timestamp = prefs.getLong(PREF_CACHE_TIMESTAMP, 0L)
        if (timestamp == 0L) return -1
        return (System.currentTimeMillis() - timestamp) / 1000 / 60
    }
    
    /**
     * Determine if we should fetch from remote server
     * 
     * STRICT 4-HOUR RETENTION RULE:
     * - Do NOT re-fetch if cache is younger than 4 hours
     * - ONLY bypass cache on:
     *   1. Local database/list is EMPTY
     *   2. User performs Manual Swipe-to-Refresh
     * 
     * @param prefs SharedPreferences instance
     * @param isManualRefresh True if user triggered refresh manually (swipe-to-refresh)
     * @return True if should fetch from remote, false to use cache
     */
    fun shouldFetchRemote(
        prefs: SharedPreferences, 
        isManualRefresh: Boolean = false,
        isConnected: Boolean = false  // Kept for API compatibility but not used
    ): Boolean {
        // EXCEPTION 1: Always fetch on manual refresh (swipe-to-refresh)
        if (isManualRefresh) {
            Log.d(TAG, "Force refresh: Manual swipe-to-refresh triggered")
            return true
        }
        
        // EXCEPTION 2: Fetch if cache is empty
        val cached = loadCachedServers(prefs)
        if (cached.isNullOrEmpty()) {
            Log.d(TAG, "Force refresh: Local cache is empty")
            return true
        }
        
        // STRICT RULE: If cache is < 4 hours old, use it
        if (isCacheValid(prefs)) {
            val ageMinutes = getCacheAgeMinutes(prefs)
            Log.d(TAG, "Using cache: ${cached.size} servers, age=$ageMinutes minutes (< 4 hours)")
            return false
        }
        
        // Cache is expired - need fresh data
        Log.d(TAG, "Cache expired: Fetching fresh data from remote")
        return true
    }
    
    /**
     * Save servers with ServerSorter filtering (discards timeout servers)
     * This ensures only reachable servers are persisted
     */
    fun saveValidServersOnly(prefs: SharedPreferences, servers: List<SstpServer>) {
        val validServers = ServerSorter.filterReachable(servers)
        val sortedServers = ServerSorter.sortByScore(validServers)
        
        if (sortedServers.isEmpty()) {
            Log.w(TAG, "No valid servers to save (all timed out)")
            return
        }
        
        saveSortedServersWithPings(prefs, sortedServers)
        Log.d(TAG, "Saved ${sortedServers.size} valid servers (discarded ${servers.size - sortedServers.size} timeout)")
    }
    
    /**
     * Clear the cache (for debugging or reset purposes)
     */
    fun clearCache(prefs: SharedPreferences) {
        prefs.edit()
            .remove(PREF_CACHED_SERVERS)
            .remove(PREF_CACHE_TIMESTAMP)
            .apply()
        Log.d(TAG, "Cache cleared")
    }
}
