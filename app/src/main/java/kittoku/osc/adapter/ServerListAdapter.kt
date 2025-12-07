package kittoku.osc.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import kittoku.osc.R
import kittoku.osc.repository.SstpServer
import java.util.Locale

class ServerListAdapter(
    private val servers: List<SstpServer>,
    private val onServerClick: (SstpServer) -> Unit
) : RecyclerView.Adapter<ServerListAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_server_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val server = servers[position]
        holder.bind(server)
    }

    override fun getItemCount() = servers.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvPing: TextView = itemView.findViewById(R.id.tv_ping)
        private val ivFlag: ImageView = itemView.findViewById(R.id.iv_flag)
        private val tvHostname: TextView = itemView.findViewById(R.id.tv_hostname)
        private val tvDetails: TextView = itemView.findViewById(R.id.tv_details)
        private val tvScoreSpeed: TextView = itemView.findViewById(R.id.tv_score_speed)

        fun bind(server: SstpServer) {
            tvPing.text = "${server.ping} ms"
            tvHostname.text = server.hostName
            tvDetails.text = "${server.countryCode} - ${server.country}"
            tvScoreSpeed.text = String.format(Locale.getDefault(), "%,d points -> %.2f Mbps", server.sessions, server.speed / 1_000_000.0)

            val flagUrl = "https://www.vpn-gate.net/images/flags/${server.countryCode}.png"
            Picasso.get().load(flagUrl).into(ivFlag)

            itemView.setOnClickListener { onServerClick(server) }
        }
    }
}

