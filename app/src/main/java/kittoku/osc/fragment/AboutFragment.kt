package kittoku.osc.fragment

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import kittoku.osc.BuildConfig
import kittoku.osc.R

/**
 * About/Credits fragment showing acknowledgments for VPN GATE and Open SSTP Client
 */
class AboutFragment : Fragment(R.layout.fragment_about) {
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Set dynamic version from BuildConfig
        view.findViewById<TextView>(R.id.tv_version)?.text = "Version ${BuildConfig.VERSION_NAME}"
        
        view.findViewById<Button>(R.id.btn_close)?.setOnClickListener {
            findNavController().navigateUp()
        }
    }
}
