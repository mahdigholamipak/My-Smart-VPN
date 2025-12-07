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
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kittoku.osc.R
import kittoku.osc.adapter.ServerListAdapter
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue
import kittoku.osc.preference.checkPreferences
import kittoku.osc.preference.toastInvalidSetting
import kittoku.osc.repository.SstpServer
import kittoku.osc.repository.VpnRepository
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.SstpVpnService

class HomeFragment : Fragment() {
    private lateinit var prefs: SharedPreferences
    private lateinit var hostnameEdit: TextInputEditText
    private lateinit var usernameEdit: TextInputEditText
    private lateinit var passwordEdit: TextInputEditText
    private lateinit var statusText: TextView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var serverList: RecyclerView
    private lateinit var serverListAdapter: ServerListAdapter
    private val vpnRepository = VpnRepository()
    private var servers = mutableListOf<SstpServer>()
    private var currentServerIndex = 0
    private val connectionHandler = Handler(Looper.getMainLooper())
    private var connectionAttemptRunnable: Runnable? = null

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "VPN_CONNECTION_STATE_CHANGED" -> {
                    val state = intent.getStringExtra("STATE")
                    statusText.text = state
                    if (state == "CONNECTED") {
                        connectionHandler.removeCallbacks(connectionAttemptRunnable!!)
                    } else if (state == "ERROR" || state == "DISCONNECTED") {
                        connectionAttemptRunnable?.let {
                            connectionHandler.removeCallbacks(it)
                            connectToNextServer()
                        }
                    }
                }
            }
        }
    }

    private val preparationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService(ACTION_VPN_CONNECT)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        hostnameEdit = view.findViewById(R.id.hostname_edit)
        usernameEdit = view.findViewById(R.id.username_edit)
        passwordEdit = view.findViewById(R.id.password_edit)
        statusText = view.findViewById(R.id.status_text)
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout)
        serverList = view.findViewById(R.id.server_list)

        serverList.layoutManager = LinearLayoutManager(requireContext())
        serverListAdapter = ServerListAdapter(servers) { server ->
            currentServerIndex = servers.indexOf(server)
            startConnectionFlow()
        }
        serverList.adapter = serverListAdapter

        view.findViewById<MaterialButton>(R.id.connect_button).setOnClickListener {
            savePreferences()

            checkPreferences(prefs)?.also {
                toastInvalidSetting(it, requireContext())
                return@setOnClickListener
            }

            VpnService.prepare(requireContext())?.also {
                preparationLauncher.launch(it)
            } ?: startVpnService(ACTION_VPN_CONNECT)
        }

        swipeRefreshLayout.setOnRefreshListener {
            loadServers()
        }

        loadPreferences()
        loadServers()

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            vpnStateReceiver, IntentFilter("VPN_CONNECTION_STATE_CHANGED")
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(vpnStateReceiver)
        connectionAttemptRunnable?.let { connectionHandler.removeCallbacks(it) }
    }

    private fun loadServers() {
        swipeRefreshLayout.isRefreshing = true
        vpnRepository.fetchSstpServers { newServers ->
            activity?.runOnUiThread {
                servers.clear()
                servers.addAll(newServers)
                serverListAdapter.notifyDataSetChanged()
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    private fun startConnectionFlow() {
        if (currentServerIndex >= servers.size) {
            statusText.text = "All servers failed"
            return
        }

        val server = servers[currentServerIndex]
        statusText.text = "Connecting to ${server.country}..."
        hostnameEdit.setText(server.hostName)
        usernameEdit.setText("vpn")
        passwordEdit.setText("vpn")
        savePreferences()

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
        servers[currentServerIndex].let { server ->
            val newScore = server.score - 1000 // Penalty
            servers[currentServerIndex] = server.copy(score = newScore)
        }
        currentServerIndex++
        startConnectionFlow()
    }

    private fun savePreferences() {
        setStringPrefValue(hostnameEdit.text.toString(), OscPrefKey.HOME_HOSTNAME, prefs)
        setStringPrefValue(usernameEdit.text.toString(), OscPrefKey.HOME_USERNAME, prefs)
        setStringPrefValue(passwordEdit.text.toString(), OscPrefKey.HOME_PASSWORD, prefs)
    }

    private fun loadPreferences() {
        hostnameEdit.setText(getStringPrefValue(OscPrefKey.HOME_HOSTNAME, prefs))
        usernameEdit.setText(getStringPrefValue(OscPrefKey.HOME_USERNAME, prefs))
        passwordEdit.setText(getStringPrefValue(OscPrefKey.HOME_PASSWORD, prefs))
    }

    private fun startVpnService(action: String) {
        val intent = Intent(requireContext(), SstpVpnService::class.java).setAction(action)

        if (action == ACTION_VPN_CONNECT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
    }
}
