package kittoku.osc.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * SharedConnectionViewModel - Activity-scoped ViewModel
 * 
 * SINGLE SOURCE OF TRUTH for VPN connection state.
 * Both HomeFragment and ManualFragment observe this ViewModel.
 * 
 * Architecture:
 * - Scoped to MainActivity (shared across all fragments)
 * - Uses LiveData with postValue() for thread-safe Main Thread updates
 * - VPN Service callbacks update this ViewModel
 * - All fragments observe and react instantly
 */
class SharedConnectionViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "SharedConnectionVM"
    }
    
    /**
     * Connection states
     */
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }
    
    /**
     * Data class holding full connection info
     */
    data class ConnectionInfo(
        val state: ConnectionState = ConnectionState.DISCONNECTED,
        val serverHostname: String? = null,
        val serverCountry: String? = null,
        val connectedAt: Long = 0L,
        val isManualConnection: Boolean = false
    )
    
    // Private mutable LiveData - only ViewModel can modify
    private val _connectionState = MutableLiveData(ConnectionState.DISCONNECTED)
    private val _connectionInfo = MutableLiveData(ConnectionInfo())
    private val _errorMessage = MutableLiveData<String?>()
    
    // Public immutable LiveData - fragments observe these
    val connectionState: LiveData<ConnectionState> = _connectionState
    val connectionInfo: LiveData<ConnectionInfo> = _connectionInfo
    val errorMessage: LiveData<String?> = _errorMessage
    
    /**
     * Update connection state - THREAD SAFE
     * Uses postValue() to ensure updates happen on Main Thread
     */
    fun updateState(newState: ConnectionState) {
        Log.d(TAG, "State update: ${_connectionState.value} -> $newState")
        _connectionState.postValue(newState)
        
        // Also update connection info
        val currentInfo = _connectionInfo.value ?: ConnectionInfo()
        _connectionInfo.postValue(currentInfo.copy(state = newState))
    }
    
    /**
     * Set connecting state with server info
     */
    fun setConnecting(hostname: String, isManual: Boolean = false) {
        Log.d(TAG, "Connecting to: $hostname (manual=$isManual)")
        _connectionState.postValue(ConnectionState.CONNECTING)
        _connectionInfo.postValue(ConnectionInfo(
            state = ConnectionState.CONNECTING,
            serverHostname = hostname,
            isManualConnection = isManual
        ))
    }
    
    /**
     * Set connected state with full info
     */
    fun setConnected(hostname: String, country: String? = null) {
        Log.d(TAG, "Connected to: $hostname")
        _connectionState.postValue(ConnectionState.CONNECTED)
        _connectionInfo.postValue(ConnectionInfo(
            state = ConnectionState.CONNECTED,
            serverHostname = hostname,
            serverCountry = country,
            connectedAt = System.currentTimeMillis()
        ))
    }
    
    /**
     * Set disconnecting state
     */
    fun setDisconnecting() {
        Log.d(TAG, "Disconnecting...")
        _connectionState.postValue(ConnectionState.DISCONNECTING)
        
        val currentInfo = _connectionInfo.value ?: ConnectionInfo()
        _connectionInfo.postValue(currentInfo.copy(state = ConnectionState.DISCONNECTING))
    }
    
    /**
     * Set disconnected state
     */
    fun setDisconnected() {
        Log.d(TAG, "Disconnected")
        _connectionState.postValue(ConnectionState.DISCONNECTED)
        _connectionInfo.postValue(ConnectionInfo(state = ConnectionState.DISCONNECTED))
    }
    
    /**
     * Set error message (cleared after being observed)
     */
    fun setError(message: String) {
        Log.e(TAG, "Error: $message")
        _errorMessage.postValue(message)
    }
    
    /**
     * Clear error after handling
     */
    fun clearError() {
        _errorMessage.postValue(null)
    }
    
    /**
     * Get current state synchronously (for non-UI checks)
     */
    fun getCurrentState(): ConnectionState {
        return _connectionState.value ?: ConnectionState.DISCONNECTED
    }
    
    /**
     * Check if currently connected
     */
    fun isConnected(): Boolean {
        return _connectionState.value == ConnectionState.CONNECTED
    }
    
    /**
     * Check if connection is in progress
     */
    fun isConnecting(): Boolean {
        return _connectionState.value == ConnectionState.CONNECTING
    }
}
