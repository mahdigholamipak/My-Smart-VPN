package kittoku.osc.fragment

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kittoku.osc.R
import kittoku.osc.adapter.ServerListAdapter
import kittoku.osc.repository.VpnRepository

class ServerListFragment : Fragment(R.layout.fragment_server_list) {
    private lateinit var serverListAdapter: ServerListAdapter
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private val vpnRepository = VpnRepository()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout)
        val serversRecyclerView = view.findViewById<RecyclerView>(R.id.servers_recycler_view)
        serversRecyclerView.layoutManager = LinearLayoutManager(context)

        serverListAdapter = ServerListAdapter(mutableListOf()) { server ->
            // Set the result to be received by the HomeFragment
            setFragmentResult("serverSelection", bundleOf("selectedHostname" to server.hostName))
            findNavController().navigateUp()
        }
        serversRecyclerView.adapter = serverListAdapter

        swipeRefreshLayout.setOnRefreshListener { loadServers() }

        loadServers()
    }

    private fun loadServers() {
        swipeRefreshLayout.isRefreshing = true
        vpnRepository.fetchSstpServers { servers ->
            activity?.runOnUiThread {
                serverListAdapter.updateData(servers)
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }
}
