package kittoku.osc.fragment

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kittoku.osc.R
import kittoku.osc.adapter.ServerListAdapter
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.setBooleanPrefValue
import kittoku.osc.preference.accessor.setIntPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue
import kittoku.osc.repository.VpnRepository
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.SstpVpnService

class ServerListFragment : Fragment() {
    private lateinit var repository: VpnRepository
    private lateinit var adapter: ServerListAdapter
    private lateinit var prefs: SharedPreferences
    private lateinit var swipeRefresh: SwipeRefreshLayout

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_server_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = VpnRepository()
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerViewServers)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        adapter = ServerListAdapter(emptyList()) { server ->
            connectToServer(server)
        }
        recyclerView.adapter = adapter

        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        swipeRefresh.setOnRefreshListener { loadServers() }

        loadServers()
    }

    private fun loadServers() {
        swipeRefresh.isRefreshing = true
        repository.fetchSstpServers { servers ->
            activity?.runOnUiThread {
                swipeRefresh.isRefreshing = false
                if (servers.isNotEmpty()) {
                    adapter.updateList(servers)
                } else {
                    Toast.makeText(context, "No servers found", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun connectToServer(server: kittoku.osc.repository.SstpServer) {
        // 1. ذخیره تنظیمات سرور در SharedPreferences
        setStringPrefValue(server.hostName, OscPrefKey.HOME_HOSTNAME, prefs)
        setStringPrefValue("vpn", OscPrefKey.HOME_USERNAME, prefs)
        setStringPrefValue("vpn", OscPrefKey.HOME_PASSWORD, prefs)
        setBooleanPrefValue(true, OscPrefKey.SSL_DO_VERIFY, prefs)
        setIntPrefValue(443, OscPrefKey.SSL_PORT, prefs)

        Toast.makeText(context, "Connecting to ${server.country}...", Toast.LENGTH_SHORT).show()

        // 2. ارسال دستور اتصال به سرویس
        val intent = Intent(requireContext(), SstpVpnService::class.java).apply {
            action = ACTION_VPN_CONNECT
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }
}