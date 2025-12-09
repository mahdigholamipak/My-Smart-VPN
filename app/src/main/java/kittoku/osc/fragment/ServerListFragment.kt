package kittoku.osc.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kittoku.osc.R
import kittoku.osc.adapter.ServerListAdapter
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.repository.VpnRepository
import kittoku.osc.service.ACTION_VPN_STATUS_CHANGED

class ServerListFragment : Fragment(R.layout.fragment_server_list) {
    companion object {
        private const val TAG = "ServerListFragment"
    }
    
    private lateinit var serverListAdapter: ServerListAdapter
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var txtStatus: TextView
    private lateinit var prefs: SharedPreferences
    private var countrySpinner: Spinner? = null
    private val vpnRepository = VpnRepository()
    
    // Track current connection status
    private var currentStatus = "DISCONNECTED"

    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("status")?.let { status ->
                Log.d(TAG, "Received VPN status broadcast: $status")
                currentStatus = status
                updateStatusUI(status)
                updateConnectedServerHighlight()
            }
        }
    }
    
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
        if (key == OscPrefKey.ROOT_STATE.name) {
            val isConnected = getBooleanPrefValue(OscPrefKey.ROOT_STATE, sharedPrefs)
            val newStatus = if (isConnected) "CONNECTED" else "DISCONNECTED"
            Log.d(TAG, "ROOT_STATE changed: $isConnected -> status: $newStatus")
            
            if (currentStatus != newStatus && currentStatus != "CONNECTING") {
                currentStatus = newStatus
                activity?.runOnUiThread {
                    updateStatusUI(newStatus)
                    updateConnectedServerHighlight()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        
        // Initialize views
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout)
        txtStatus = view.findViewById(R.id.txtStatus)
        countrySpinner = view.findViewById(R.id.spinner_country)
        val serversRecyclerView = view.findViewById<RecyclerView>(R.id.servers_recycler_view)
        serversRecyclerView.layoutManager = LinearLayoutManager(context)

        // Initialize adapter with connected server highlighting
        serverListAdapter = ServerListAdapter(mutableListOf()) { server ->
            Log.d(TAG, "Server selected: ${server.hostName}")
            setFragmentResult("serverSelection", bundleOf("selectedHostname" to server.hostName))
            findNavController().navigateUp()
        }
        serversRecyclerView.adapter = serverListAdapter

        // Setup pull-to-refresh
        swipeRefreshLayout.setOnRefreshListener { 
            Log.d(TAG, "Pull to refresh triggered")
            loadServers() 
        }

        // Set initial status
        val isCurrentlyConnected = getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)
        currentStatus = if (isCurrentlyConnected) "CONNECTED" else "DISCONNECTED"
        updateStatusUI(currentStatus)
        
        // Load servers
        loadServers()
    }

    private fun loadServers() {
        Log.d(TAG, "Loading servers...")
        swipeRefreshLayout.isRefreshing = true
        
        vpnRepository.fetchSstpServers { servers ->
            Log.d(TAG, "Received ${servers.size} servers")
            activity?.runOnUiThread {
                serverListAdapter.updateData(servers)
                swipeRefreshLayout.isRefreshing = false
                
                // Update connected server highlighting
                updateConnectedServerHighlight()
                
                // Setup country filter spinner
                setupCountryFilter()
                
                if (servers.isEmpty()) {
                    Log.w(TAG, "No servers loaded - check network or CSV parsing")
                }
            }
        }
    }
    
    /**
     * Setup country filter spinner with unique countries
     */
    private fun setupCountryFilter() {
        countrySpinner?.let { spinner ->
            val countries = serverListAdapter.getUniqueCountries()
            val countryNames = mutableListOf("All Countries")
            countryNames.addAll(countries.map { "${it.second} (${it.first})" })
            
            val adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                countryNames
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
            
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position == 0) {
                        // All countries
                        serverListAdapter.filterByCountry(null)
                    } else {
                        val selectedCountry = countries[position - 1]
                        serverListAdapter.filterByCountry(selectedCountry.first)
                    }
                }
                
                override fun onNothingSelected(parent: AdapterView<*>?) {
                    serverListAdapter.filterByCountry(null)
                }
            }
        }
    }
    
    /**
     * Update the connected server highlighting in the adapter
     */
    private fun updateConnectedServerHighlight() {
        if (!isAdded) return
        
        val isConnected = getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)
        if (isConnected) {
            try {
                val connectedHostname = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs)
                serverListAdapter.setConnectedServer(connectedHostname)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting connected hostname", e)
                serverListAdapter.setConnectedServer(null)
            }
        } else {
            serverListAdapter.setConnectedServer(null)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - registering receivers")
        
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            vpnStatusReceiver,
            IntentFilter(ACTION_VPN_STATUS_CHANGED)
        )
        
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        
        // Refresh status and highlighting
        val isCurrentlyConnected = getBooleanPrefValue(OscPrefKey.ROOT_STATE, prefs)
        val status = if (isCurrentlyConnected) "CONNECTED" else "DISCONNECTED"
        if (currentStatus != "CONNECTING") {
            currentStatus = status
            updateStatusUI(status)
        }
        updateConnectedServerHighlight()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause - unregistering receivers")
        
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(vpnStatusReceiver)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun updateStatusUI(status: String) {
        Log.d(TAG, "Updating status UI: $status")
        
        if (!isAdded) return
        
        when {
            status == "CONNECTING" -> {
                txtStatus.text = "Connecting..."
                txtStatus.setTextColor(Color.parseColor("#FF8C00"))
                txtStatus.setBackgroundColor(Color.parseColor("#FFF3CD"))
            }
            status == "CONNECTED" -> {
                txtStatus.text = "✓ Secured"
                txtStatus.setTextColor(Color.parseColor("#155724"))
                txtStatus.setBackgroundColor(Color.parseColor("#D4EDDA"))
            }
            status == "DISCONNECTED" -> {
                txtStatus.text = "Idle"
                txtStatus.setTextColor(Color.parseColor("#6C757D"))
                txtStatus.setBackgroundColor(Color.parseColor("#E9ECEF"))
            }
            status.startsWith("ERROR") -> {
                txtStatus.text = status
                txtStatus.setTextColor(Color.parseColor("#721C24"))
                txtStatus.setBackgroundColor(Color.parseColor("#F8D7DA"))
            }
            else -> {
                txtStatus.text = status
                txtStatus.setTextColor(Color.GRAY)
                txtStatus.setBackgroundColor(Color.parseColor("#E0E0E0"))
            }
        }
    }
}
