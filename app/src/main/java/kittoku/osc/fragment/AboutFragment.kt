package kittoku.osc.fragment

import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import kittoku.osc.R

/**
 * About/Credits fragment showing acknowledgments for VPN GATE and Open SSTP Client
 */
class AboutFragment : Fragment(R.layout.fragment_about) {
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        view.findViewById<Button>(R.id.btn_close)?.setOnClickListener {
            findNavController().navigateUp()
        }
    }
}
