package kittoku.osc.fragment

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import kittoku.osc.adapter.ServerListAdapter
import kittoku.osc.databinding.FragmentServerListBinding
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.repository.SstpServer
import kittoku.osc.repository.VpnRepository
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.SstpVpnService

class ServerListFragment : Fragment() {
    private var _binding: FragmentServerListBinding? = null
    private val binding get() = _binding!!

    private lateinit var listAdapter: ServerListAdapter
    private val vpnRepository = VpnRepository()

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpn()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentServerListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()

        binding.swipeRefreshLayout.setOnRefreshListener { fetchServers() }

        fetchServers()
    }

    private fun setupRecyclerView() {
        listAdapter = ServerListAdapter { server ->
            prepareAndStartVpn(server)
        }
        binding.recyclerView.adapter = listAdapter
    }

    private fun fetchServers() {
        binding.progressBar.visibility = View.VISIBLE
        binding.swipeRefreshLayout.isRefreshing = true

        vpnRepository.fetchSstpServers { servers ->
            activity?.runOnUiThread {
                listAdapter.updateData(servers)
                binding.progressBar.visibility = View.GONE
                binding.swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    private fun prepareAndStartVpn(server: SstpServer) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        prefs.edit().also { 
            it.putString(OscPrefKey.HOST_ADDRESS.name, server.hostName)
            it.putString(OscPrefKey.SSL_PORT.name, "443")
            it.putString(OscPrefKey.PPP_USERNAME.name, "vpn")
            it.putString(OscPrefKey.PPP_PASSWORD.name, "vpn")
            it.apply()
        }

        val intent = VpnService.prepare(context)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        val intent = Intent(requireContext(), SstpVpnService::class.java).setAction(ACTION_VPN_CONNECT)
        requireContext().startService(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
