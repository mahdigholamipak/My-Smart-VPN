package kittoku.osc.fragment

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import kittoku.osc.R
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Fragment for viewing runtime logs directly in the app
 * Helps debug connection issues without needing external tools
 */
class LogViewerFragment : Fragment(R.layout.fragment_log_viewer) {
    
    private lateinit var tvLogContent: TextView
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        tvLogContent = view.findViewById(R.id.tv_log_content)
        
        view.findViewById<Button>(R.id.btn_refresh_logs)?.setOnClickListener {
            loadLogs()
        }
        
        view.findViewById<Button>(R.id.btn_clear_logs)?.setOnClickListener {
            tvLogContent.text = "Logs cleared."
        }
        
        view.findViewById<Button>(R.id.btn_close_logs)?.setOnClickListener {
            findNavController().navigateUp()
        }
        
        // Load logs on start
        loadLogs()
    }
    
    /**
     * Load system logs filtered for our app
     */
    private fun loadLogs() {
        tvLogContent.text = "Loading logs..."
        
        Thread {
            try {
                val logBuilder = StringBuilder()
                
                // Try to read logcat for our package
                val process = Runtime.getRuntime().exec(arrayOf(
                    "logcat", "-d", "-t", "200",
                    "-v", "time",
                    "HomeFragment:V",
                    "VpnRepository:V", 
                    "SstpVpnService:V",
                    "ServerListFragment:V",
                    "*:S"  // Silence everything else
                ))
                
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        logBuilder.appendLine(line)
                    }
                }
                
                // Also try to read log file if it exists
                val logFile = File(requireContext().filesDir, "vpn.log")
                if (logFile.exists()) {
                    logBuilder.appendLine("\n--- Log File ---")
                    logBuilder.append(logFile.readText().takeLast(5000))
                }
                
                val logs = logBuilder.toString().ifEmpty { "No logs found." }
                
                activity?.runOnUiThread {
                    if (isAdded) {
                        tvLogContent.text = logs
                    }
                }
                
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    if (isAdded) {
                        tvLogContent.text = "Error loading logs: ${e.message}\n\n" +
                                "Note: Some devices restrict logcat access."
                    }
                }
            }
        }.start()
    }
}
