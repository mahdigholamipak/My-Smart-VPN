package kittoku.osc.fragment

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kittoku.osc.R
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue
import kittoku.osc.repository.ConnectionStateManager
import kittoku.osc.repository.HostnameHistoryManager
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.ACTION_VPN_DISCONNECT
import kittoku.osc.service.ACTION_VPN_STATUS_CHANGED
import kittoku.osc.service.SstpVpnService

/**
 * Fragment for manual VPN server connection
 * Features:
 * - Pre-fills last used hostname
 * - Smart Connect/Disconnect button based on connection state
 * - Real-time state synchronization with VPN service
 */
class ManualConnectFragment : Fragment(R.layout.fragment_manual_connect) {
    
    private lateinit var prefs: SharedPreferences
    private lateinit var etHostname: TextInputEditText
    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnConnect: MaterialButton
    
    private val preparationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
            // Don't navigate away immediately - let user see state changes
        } else {
            // User cancelled VPN permission
            ConnectionStateManager.setState(
                requireContext(),
                ConnectionStateManager.ConnectionState.DISCONNECTED
            )
            updateButtonState()
        }
    }
    
    // Listen for VPN service state changes (direct from service)
    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status") ?: return
            
            when (status) {
                "CONNECTING" -> {
                    ConnectionStateManager.setState(
                        requireContext(),
                        ConnectionStateManager.ConnectionState.CONNECTING
                    )
                }
                "CONNECTED" -> {
                    ConnectionStateManager.setState(
                        requireContext(),
                        ConnectionStateManager.ConnectionState.CONNECTED
                    )
                }
                "DISCONNECTED" -> {
                    ConnectionStateManager.setState(
                        requireContext(),
                        ConnectionStateManager.ConnectionState.DISCONNECTED
                    )
                }
            }
            updateButtonState()
        }
    }
    
    // Listen for ConnectionStateManager broadcasts
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateButtonState()
        }
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        
        etHostname = view.findViewById(R.id.et_hostname)
        etUsername = view.findViewById(R.id.et_username)
        etPassword = view.findViewById(R.id.et_password)
        btnConnect = view.findViewById(R.id.btn_connect_manual)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btn_cancel)
        
        // Pre-fill last used hostname from SharedPreferences
        prefillLastHostname()
        
        // Set initial button state
        updateButtonState()
        
        btnConnect.setOnClickListener {
            val currentState = ConnectionStateManager.getCurrentState()
            
            when (currentState) {
                ConnectionStateManager.ConnectionState.CONNECTED,
                ConnectionStateManager.ConnectionState.CONNECTING -> {
                    // Disconnect
                    disconnectVpn()
                }
                else -> {
                    // Connect
                    attemptConnection()
                }
            }
        }
        
        btnCancel.setOnClickListener {
            findNavController().navigateUp()
        }
    }
    
    /**
     * Pre-fill the hostname field with the last used value
     */
    private fun prefillLastHostname() {
        // First try to get from HostnameHistoryManager (most recent)
        val history = HostnameHistoryManager.getHistory(requireContext())
        if (history.isNotEmpty()) {
            etHostname.setText(history.first())
            return
        }
        
        // Fallback to HOME_HOSTNAME preference
        val lastHostname = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)
        if (lastHostname.isNotEmpty()) {
            etHostname.setText(lastHostname)
        }
    }
    
    private fun attemptConnection() {
        val hostname = etHostname.text?.toString()?.trim() ?: ""
        val username = etUsername.text?.toString()?.trim() ?: ""
        val password = etPassword.text?.toString() ?: ""
        
        if (hostname.isEmpty()) {
            Toast.makeText(context, "Please enter a hostname", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (username.isEmpty()) {
            Toast.makeText(context, "Please enter a username", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Save hostname to history
        HostnameHistoryManager.saveHostname(requireContext(), hostname)
        
        // Save to preferences
        setStringPrefValue(hostname, OscPrefKey.HOME_HOSTNAME, prefs)
        setStringPrefValue(username, OscPrefKey.HOME_USERNAME, prefs)
        setStringPrefValue(password, OscPrefKey.HOME_PASSWORD, prefs)
        
        // IMMEDIATELY update UI to "Connecting" state
        ConnectionStateManager.setState(
            requireContext(),
            ConnectionStateManager.ConnectionState.CONNECTING,
            serverName = hostname,
            isManual = true
        )
        updateButtonState()
        
        // Start connection
        VpnService.prepare(requireContext())?.also {
            preparationLauncher.launch(it)
        } ?: run {
            startVpnService()
        }
    }
    
    private fun disconnectVpn() {
        // IMMEDIATELY update UI to "Disconnecting" state
        ConnectionStateManager.setState(
            requireContext(),
            ConnectionStateManager.ConnectionState.DISCONNECTING
        )
        updateButtonState()
        
        // Send disconnect intent on background thread to avoid blocking
        Thread {
            try {
                val intent = Intent(requireContext(), SstpVpnService::class.java)
                    .setAction(ACTION_VPN_DISCONNECT)
                requireContext().startService(intent)
            } catch (e: Exception) {
                // Service might not be available
                activity?.runOnUiThread {
                    ConnectionStateManager.setState(
                        requireContext(),
                        ConnectionStateManager.ConnectionState.DISCONNECTED
                    )
                    updateButtonState()
                }
            }
        }.start()
        
        Toast.makeText(context, "Disconnecting...", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateButtonState() {
        if (!isAdded || !::btnConnect.isInitialized) return
        
        val currentState = ConnectionStateManager.getCurrentState()
        
        activity?.runOnUiThread {
            when (currentState) {
                ConnectionStateManager.ConnectionState.CONNECTED -> {
                    btnConnect.text = "Disconnect"
                    btnConnect.setBackgroundColor(resources.getColor(android.R.color.holo_red_dark, null))
                    btnConnect.isEnabled = true
                }
                ConnectionStateManager.ConnectionState.CONNECTING -> {
                    btnConnect.text = "Connecting..."
                    btnConnect.setBackgroundColor(resources.getColor(android.R.color.holo_orange_dark, null))
                    btnConnect.isEnabled = false
                }
                ConnectionStateManager.ConnectionState.DISCONNECTING -> {
                    btnConnect.text = "Disconnecting..."
                    btnConnect.setBackgroundColor(resources.getColor(android.R.color.darker_gray, null))
                    btnConnect.isEnabled = false
                }
                ConnectionStateManager.ConnectionState.DISCONNECTED -> {
                    btnConnect.text = "Connect"
                    btnConnect.setBackgroundColor(resources.getColor(android.R.color.holo_green_dark, null))
                    btnConnect.isEnabled = true
                }
            }
        }
    }
    
    private fun startVpnService() {
        val intent = Intent(requireContext(), SstpVpnService::class.java)
            .setAction(ACTION_VPN_CONNECT)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Register for VPN service status broadcasts (direct from service)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            vpnStatusReceiver,
            IntentFilter(ACTION_VPN_STATUS_CHANGED)
        )
        // Register for ConnectionStateManager broadcasts
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            stateReceiver,
            IntentFilter(ConnectionStateManager.ACTION_STATE_CHANGED)
        )
        updateButtonState()
    }
    
    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(vpnStatusReceiver)
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(stateReceiver)
    }
}
