package kittoku.osc.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import kittoku.osc.R
import kittoku.osc.databinding.ItemServerCardBinding
import kittoku.osc.repository.SstpServer
import java.util.Locale

class ServerListAdapter(private val onConnectClick: (SstpServer) -> Unit) : RecyclerView.Adapter<ServerListAdapter.ViewHolder>() {

    private var servers = emptyList<SstpServer>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemServerCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(servers[position])
    }

    override fun getItemCount() = servers.size

    fun updateData(newServers: List<SstpServer>) {
        servers = newServers
        notifyDataSetChanged()
    }

    inner class ViewHolder(private val binding: ItemServerCardBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.btnConnect.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onConnectClick(servers[adapterPosition])
                }
            }
        }

        fun bind(server: SstpServer) {
            val flag = server.countryCode.let { code ->
                try {
                    val firstLetter = Character.toString(code[0]).toUpperCase(Locale.US)
                    val secondLetter = Character.toString(code[1]).toUpperCase(Locale.US)
                    (Character.toChars(firstLetter.codePointAt(0) - 'A'.codePointAt(0) + 0x1F1E6)
                        + Character.toChars(secondLetter.codePointAt(0) - 'A'.codePointAt(0) + 0x1F1E6))
                        .joinToString("")
                } catch (e: Exception) {
                    ""
                }
            }

            binding.tvCountry.text = "${server.country} $flag"
            binding.tvHost.text = "${server.ip} / ${server.hostName}"
            binding.tvSpeed.text = "${server.speed / 1_000_000} Mbps"
            binding.tvSessions.text = "Sessions: ${server.sessions}"
        }
    }
}