package kittoku.osc.fragment

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import kittoku.osc.R
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.setStringPrefValue
import kittoku.osc.preference.checkPreferences
import kittoku.osc.preference.toastInvalidSetting
import kittoku.osc.repository.SstpServer
import kittoku.osc.repository.VpnRepository
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.ACTION_VPN_DISCONNECT
import kittoku.osc.service.ACTION_VPN_STATUS_CHANGED
import kittoku.osc.service.SstpVpnService

class HomeFragment : Fragment(R.layout.fragment_home) {
    private lateinit var prefs: SharedPreferences
    private lateinit var tvStatus: TextView
    private lateinit var btnConnect: Button
    private val vpnRepository = VpnRepository()
    private var servers = mutableListOf<SstpServer>()
    private var currentServerIndex = 0
    private val connectionHandler = Handler(Looper.getMainLooper())
    private var connectionAttemptRunnable: Runnable? = null

    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra("status") ?: "DISCONNECTED"
            updateStatus(status)

            if (status == "CONNECTED") {
                connectionAttemptRunnable?.let { connectionHandler.removeCallbacks(it) }
            } else if (status.startsWith("ERROR") || status == "DISCONNECTED") {
                connectionAttemptRunnable?.let {
                    connectionHandler.removeCallbacks(it)
                    connectToNextServer()
                }
            }
        }
    }

    private val preparationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService(ACTION_VPN_CONNECT)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setFragmentResultListener("serverSelection") { _, bundle ->
            val selectedHostname = bundle.getString("selectedHostname")
            if (selectedHostname != null) {
                // Save the selected hostname and start the connection
                prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                setStringPrefValue(selectedHostname, OscPrefKey.HOME_HOSTNAME, prefs)
                setStringPrefValue("vpn", OscPrefKey.HOME_USERNAME, prefs)
                setStringPrefValue("vpn", OscPrefKey.HOME_PASSWORD, prefs)
                startSingleConnection(selectedHostname)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        tvStatus = view.findViewById(R.id.tv_status)
        btnConnect = view.findViewById(R.id.btn_connect)
        val btnServerList = view.findViewById<Button>(R.id.btn_server_list)

        btnConnect.setOnClickListener {
            val intent = Intent(context, SstpVpnService::class.java)
            if (btnConnect.text == "CONNECT") {
                loadServersAndConnect()
            } else {
                intent.action = ACTION_VPN_DISCONNECT
                context?.startService(intent)
            }
        }

        btnServerList.setOnClickListener {
            findNavController().navigate(R.id.action_HomeFragment_to_ServerListFragment)
        }

        updateStatus("DISCONNECTED")
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ACTION_VPN_STATUS_CHANGED)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(vpnStatusReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(vpnStatusReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        connectionAttemptRunnable?.let { connectionHandler.removeCallbacks(it) }
    }

    private fun loadServersAndConnect() {
        vpnRepository.fetchSstpServers { newServers ->
            activity?.runOnUiThread {
                servers.clear()
                servers.addAll(newServers)
                currentServerIndex = 0
                startConnectionFlow()
            }
        }
    }

    private fun startSingleConnection(hostname: String) {
        updateStatus("Connecting...")

        // Set up a 10-second timeout
        connectionAttemptRunnable = Runnable {
            updateStatus("Connection timed out")
        }
        connectionHandler.postDelayed(connectionAttemptRunnable!!, 10000)

        VpnService.prepare(requireContext())?.also {
            preparationLauncher.launch(it)
        } ?: startVpnService(ACTION_VPN_CONNECT)
    }

    private fun startConnectionFlow() {
        if (currentServerIndex >= servers.size) {
            updateStatus("All servers failed")
            return
        }

        val server = servers[currentServerIndex]
        updateStatus("Connecting to ${server.country}...")

        setStringPrefValue(server.hostName, OscPrefKey.HOME_HOSTNAME, prefs)

        checkPreferences(prefs)?.also {
            toastInvalidSetting(it, requireContext())
            connectToNextServer()
            return
        }

        connectionAttemptRunnable = Runnable {
            connectToNextServer()
        }
        connectionHandler.postDelayed(connectionAttemptRunnable!!, 10000) // 10-second timeout

        VpnService.prepare(requireContext())?.also {
            preparationLauncher.launch(it)
        } ?: startVpnService(ACTION_VPN_CONNECT)
    }

    private fun connectToNextServer() {
        currentServerIndex++
        startConnectionFlow()
    }

    private fun updateStatus(status: String) {
        tvStatus.text = status
        when {
            status.equals("CONNECTED", ignoreCase = true) -> {
                btnConnect.text = "DISCONNECT"
                tvStatus.setTextColor(Color.GREEN)
            }
            status.startsWith("Connecting", ignoreCase = true) -> {
                btnConnect.text = "CONNECTING..."
                tvStatus.setTextColor(Color.YELLOW)
            }
            else -> {
                btnConnect.text = "CONNECT"
                tvStatus.setTextColor(Color.GRAY)
            }
        }
    }

    private fun startVpnService(action: String) {
        val intent = Intent(requireContext(), SstpVpnService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
    }
}
