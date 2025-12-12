package kittoku.osc.viewmodel

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kittoku.osc.repository.ServerCache
import kittoku.osc.repository.ServerSorter
import kittoku.osc.repository.SstpServer
import kittoku.osc.repository.VpnRepository

/**
 * ServerViewModel - Provides REACTIVE server list updates
 * 
 * CRITICAL: This ViewModel ensures immediate UI updates when:
 * 1. Servers are fetched from remote
 * 2. Servers are filtered (timeouts removed)
 * 3. Servers are sorted by Quality Score
 * 
 * The Fragment observes LiveData and calls adapter.submitList() instantly.
 * User NEVER has to navigate out/back to see updated list.
 */
class ServerViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "ServerViewModel"
    }
    
    private val vpnRepository = VpnRepository()
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)
    
    // LiveData for reactive updates - Fragment observes this
    private val _servers = MutableLiveData<List<SstpServer>>(emptyList())
    val servers: LiveData<List<SstpServer>> = _servers
    
    // Loading state
    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading
    
    // Error messages
    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error
    
    // Ping progress
    private val _pingProgress = MutableLiveData<Pair<Int, Int>>()  // (current, total)
    val pingProgress: LiveData<Pair<Int, Int>> = _pingProgress
    
    init {
        // Load cached servers on init
        loadCachedServers()
    }
    
    /**
     * Load servers from cache (instant startup)
     */
    private fun loadCachedServers() {
        viewModelScope.launch(Dispatchers.IO) {
            val cached = ServerCache.loadSortedServersWithPings(prefs)
            if (!cached.isNullOrEmpty()) {
                // Apply QoS sorting to cached data
                val sorted = ServerSorter.sortByScore(cached)
                withContext(Dispatchers.Main) {
                    _servers.value = sorted
                    Log.d(TAG, "Loaded ${sorted.size} servers from cache")
                }
            }
        }
    }
    
    /**
     * Fetch servers from remote and update UI IMMEDIATELY
     * 
     * Flow:
     * 1. Fetch CSV from remote
     * 2. Ping all servers (parallel)
     * 3. Filter out timeouts (ping = -1)
     * 4. Sort by Quality Score
     * 5. Save to cache
     * 6. Emit to LiveData -> UI updates instantly
     */
    fun fetchAndRefreshServers(forceRefresh: Boolean = false) {
        if (_isLoading.value == true) {
            Log.d(TAG, "Already loading, skipping duplicate request")
            return
        }
        
        // Check cache validity
        if (!forceRefresh && !ServerCache.shouldFetchRemote(prefs, isManualRefresh = false)) {
            Log.d(TAG, "Cache valid, using cached servers")
            loadCachedServers()
            return
        }
        
        _isLoading.postValue(true)
        
        vpnRepository.fetchSstpServers { rawServers ->
            if (rawServers.isEmpty()) {
                _isLoading.postValue(false)
                _error.postValue("Failed to fetch servers")
                return@fetchSstpServers
            }
            
            Log.d(TAG, "Fetched ${rawServers.size} raw servers, starting ping...")
            
            // Ping all servers with progress updates
            vpnRepository.measureRealPingsParallel(
                servers = rawServers,
                onServerUpdated = { index, server ->
                    // Live update: emit each server as it's pinged
                    updateSingleServer(server)
                },
                onProgress = { current, total ->
                    _pingProgress.postValue(Pair(current, total))
                },
                onComplete = { pingedServers ->
                    // CRITICAL: Filter out timeouts, sort by QoS, emit immediately
                    val validServers = ServerSorter.filterReachable(pingedServers)
                    val sortedServers = ServerSorter.sortByScore(validServers)
                    
                    Log.d(TAG, "Ping complete: ${sortedServers.size} valid servers (${pingedServers.size - sortedServers.size} timeouts filtered)")
                    
                    // Save to cache
                    ServerCache.saveValidServersOnly(prefs, pingedServers)
                    
                    // EMIT TO LIVEDATA -> UI UPDATES INSTANTLY
                    _servers.postValue(sortedServers)
                    _isLoading.postValue(false)
                }
            )
        }
    }
    
    /**
     * Update a single server in the list (for live ping updates)
     */
    private fun updateSingleServer(updatedServer: SstpServer) {
        val currentList = _servers.value?.toMutableList() ?: mutableListOf()
        val index = currentList.indexOfFirst { it.hostName == updatedServer.hostName }
        
        if (index >= 0) {
            currentList[index] = updatedServer
            // Re-sort after update
            val sorted = ServerSorter.sortByScore(currentList)
            _servers.postValue(sorted)
        }
    }
    
    /**
     * Get the BEST server (highest Quality Score)
     * Used by Smart Connect logic
     */
    fun getBestServer(): SstpServer? {
        val currentServers = _servers.value ?: return null
        return ServerSorter.getBestServer(currentServers)
    }
    
    /**
     * Get top N servers by Quality Score
     */
    fun getTopServers(count: Int = 3): List<SstpServer> {
        val currentServers = _servers.value ?: return emptyList()
        return ServerSorter.getTopServers(currentServers, count)
    }
    
    /**
     * Force refresh (manual swipe-to-refresh)
     */
    fun forceRefresh() {
        fetchAndRefreshServers(forceRefresh = true)
    }
    
    /**
     * Clear error after handling
     */
    fun clearError() {
        _error.postValue(null)
    }
}
