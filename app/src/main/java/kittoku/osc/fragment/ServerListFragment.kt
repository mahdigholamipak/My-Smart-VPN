package kittoku.osc.fragment

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kittoku.osc.R
import kittoku.osc.adapter.ServerListAdapter
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.setStringPrefValue
import kittoku.osc.repository.VpnRepository

class ServerListFragment : Fragment(R.layout.fragment_server_list) {
    private lateinit var serverListAdapter: ServerListAdapter
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private val vpnRepository = VpnRepository()
    private lateinit var prefs: SharedPreferences

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout)
        val serversRecyclerView = view.findViewById<RecyclerView>(R.id.servers_recycler_view)
        serversRecyclerView.layoutManager = LinearLayoutManager(context)

        swipeRefreshLayout.setOnRefreshListener { loadServers() }

        loadServers()
    }

    private fun loadServers() {
        swipeRefreshLayout.isRefreshing = true
        vpnRepository.fetchSstpServers { servers ->
            activity?.runOnUiThread {
                serverListAdapter = ServerListAdapter(servers) { server ->
                    setStringPrefValue(server.hostName, OscPrefKey.HOME_HOSTNAME, prefs)
                    setStringPrefValue("vpn", OscPrefKey.HOME_USERNAME, prefs)
                    setStringPrefValue("vpn", OscPrefKey.HOME_PASSWORD, prefs)
                    findNavController().navigateUp()
                }
                view?.findViewById<RecyclerView>(R.id.servers_recycler_view)?.adapter = serverListAdapter
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }
}
