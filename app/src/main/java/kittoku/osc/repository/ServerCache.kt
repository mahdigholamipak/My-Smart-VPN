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
     * @param prefs SharedPreferences instance
     * @param isManualRefresh True if user triggered refresh manually
     * @param isConnected True if VPN is currently connected
     * @return True if should fetch from remote, false to use cache
     */
    fun shouldFetchRemote(
        prefs: SharedPreferences, 
        isManualRefresh: Boolean = false,
        isConnected: Boolean = false
    ): Boolean {
        // Always fetch on manual refresh
        if (isManualRefresh) {
            Log.d(TAG, "Manual refresh requested - fetching remote")
            return true
        }
        
        // Fetch if cache is empty
        val cached = loadCachedServers(prefs)
        if (cached.isNullOrEmpty()) {
            Log.d(TAG, "Cache empty - fetching remote")
            return true
        }
        
        // If cache expired AND connected, fetch new data
        if (!isCacheValid(prefs) && isConnected) {
            Log.d(TAG, "Cache expired and connected - fetching remote")
            return true
        }
        
        Log.d(TAG, "Using cached data (${cached.size} servers)")
        return false
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
