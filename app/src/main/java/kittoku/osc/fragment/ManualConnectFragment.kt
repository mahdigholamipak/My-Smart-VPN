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
import androidx.fragment.app.activityViewModels
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kittoku.osc.R
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue
import kittoku.osc.repository.HostnameHistoryManager
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.ACTION_VPN_DISCONNECT
import kittoku.osc.service.ACTION_VPN_STATUS_CHANGED
import kittoku.osc.service.SstpVpnService
import kittoku.osc.viewmodel.SharedConnectionViewModel

/**
 * Fragment for manual VPN server connection
 * 
 * ARCHITECTURE:
 * - Uses SharedConnectionViewModel (Activity-scoped) for state
 * - Observes LiveData for instant UI updates
 * - No more UI freezing issues
 */
class ManualConnectFragment : Fragment(R.layout.fragment_manual_connect) {
    
    // Activity-scoped ViewModel - shared with HomeFragment
    private val connectionViewModel: SharedConnectionViewModel by activityViewModels()
    
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
        } else {
            // User cancelled VPN permission
            connectionViewModel.setDisconnected()
        }
    }
    
    // Listen for VPN service status broadcasts
    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status") ?: return
            
            // Update ViewModel based on service status
            when (status) {
                "CONNECTING" -> connectionViewModel.updateState(SharedConnectionViewModel.ConnectionState.CONNECTING)
                "CONNECTED" -> connectionViewModel.setConnected(
                    connectionViewModel.connectionInfo.value?.serverHostname ?: ""
                )
                "DISCONNECTED" -> connectionViewModel.setDisconnected()
            }
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
        
        // Pre-fill last used hostname
        prefillLastHostname()
        
        // CRITICAL: Observe ViewModel LiveData for instant UI updates
        observeConnectionState()
        
        btnConnect.setOnClickListener {
            when (connectionViewModel.getCurrentState()) {
                SharedConnectionViewModel.ConnectionState.CONNECTED,
                SharedConnectionViewModel.ConnectionState.CONNECTING -> {
                    disconnectVpn()
                }
                else -> {
                    attemptConnection()
                }
            }
        }
        
        btnCancel.setOnClickListener {
            findNavController().navigateUp()
        }
    }
    
    /**
     * Observe ViewModel for instant UI updates
     * Uses LiveData.observe() which always runs on Main Thread
     */
    private fun observeConnectionState() {
        connectionViewModel.connectionState.observe(viewLifecycleOwner) { state ->
            updateButtonForState(state)
        }
        
        connectionViewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                connectionViewModel.clearError()
            }
        }
    }
    
    /**
     * Update button UI based on state - runs on Main Thread via LiveData
     */
    private fun updateButtonForState(state: SharedConnectionViewModel.ConnectionState) {
        when (state) {
            SharedConnectionViewModel.ConnectionState.CONNECTED -> {
                btnConnect.text = "Disconnect"
                btnConnect.setBackgroundColor(resources.getColor(android.R.color.holo_red_dark, null))
                btnConnect.isEnabled = true
            }
            SharedConnectionViewModel.ConnectionState.CONNECTING -> {
                btnConnect.text = "Connecting..."
                btnConnect.setBackgroundColor(resources.getColor(android.R.color.holo_orange_dark, null))
                btnConnect.isEnabled = false
            }
            SharedConnectionViewModel.ConnectionState.DISCONNECTING -> {
                btnConnect.text = "Disconnecting..."
                btnConnect.setBackgroundColor(resources.getColor(android.R.color.darker_gray, null))
                btnConnect.isEnabled = false
            }
            SharedConnectionViewModel.ConnectionState.DISCONNECTED -> {
                btnConnect.text = "Connect"
                btnConnect.setBackgroundColor(resources.getColor(android.R.color.holo_green_dark, null))
                btnConnect.isEnabled = true
            }
        }
    }
    
    private fun prefillLastHostname() {
        val history = HostnameHistoryManager.getHistory(requireContext())
        if (history.isNotEmpty()) {
            etHostname.setText(history.first())
            return
        }
        
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
        
        // UPDATE VIEWMODEL - this triggers UI update in BOTH fragments instantly
        connectionViewModel.setConnecting(hostname, isManual = true)
        
        // Start connection
        VpnService.prepare(requireContext())?.also {
            preparationLauncher.launch(it)
        } ?: run {
            startVpnService()
        }
    }
    
    private fun disconnectVpn() {
        // UPDATE VIEWMODEL - instant UI update
        connectionViewModel.setDisconnecting()
        
        val intent = Intent(requireContext(), SstpVpnService::class.java)
            .setAction(ACTION_VPN_DISCONNECT)
        requireContext().startService(intent)
        
        Toast.makeText(context, "Disconnecting...", Toast.LENGTH_SHORT).show()
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
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            vpnStatusReceiver,
            IntentFilter(ACTION_VPN_STATUS_CHANGED)
        )
    }
    
    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(vpnStatusReceiver)
    }
}
