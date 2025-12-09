package kittoku.osc.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import kittoku.osc.R
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Fragment for viewing runtime logs directly in the app
 * 
 * ISSUE #2 FIX: Log text is now selectable and copyable
 */
class LogViewerFragment : Fragment(R.layout.fragment_log_viewer) {
    
    private lateinit var tvLogContent: TextView
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        tvLogContent = view.findViewById(R.id.tv_log_content)
        
        view.findViewById<Button>(R.id.btn_refresh_logs)?.setOnClickListener {
            loadLogs()
        }
        
        // ISSUE #2 FIX: Copy to clipboard functionality
        view.findViewById<Button>(R.id.btn_copy_logs)?.setOnClickListener {
            copyLogsToClipboard()
        }
        
        view.findViewById<Button>(R.id.btn_clear_logs)?.setOnClickListener {
            tvLogContent.text = "Logs cleared."
        }
        
        view.findViewById<Button>(R.id.btn_close_logs)?.setOnClickListener {
            findNavController().navigateUp()
        }
        
        loadLogs()
    }
    
    /**
     * ISSUE #2 FIX: Copy logs to clipboard for sharing
     */
    private fun copyLogsToClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("VPN Logs", tvLogContent.text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
    }
    
    private fun loadLogs() {
        tvLogContent.text = "Loading logs..."
        
        Thread {
            try {
                val logBuilder = StringBuilder()
                
                // Try to read logcat for our package
                val process = Runtime.getRuntime().exec(arrayOf(
                    "logcat", "-d", "-t", "300",
                    "-v", "time",
                    "HomeFragment:V",
                    "VpnRepository:V", 
                    "SstpVpnService:V",
                    "ServerListFragment:V",
                    "SSLTerminal:V",
                    "PPPTerminal:V",
                    "*:S"
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
                    logBuilder.appendLine("\n--- Log File (last 10KB) ---")
                    logBuilder.append(logFile.readText().takeLast(10000))
                }
                
                val logs = logBuilder.toString().ifEmpty { 
                    "No logs found.\n\nTip: Long-press to select text, or use the Copy button." 
                }
                
                activity?.runOnUiThread {
                    if (isAdded) {
                        tvLogContent.text = logs
                    }
                }
                
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    if (isAdded) {
                        tvLogContent.text = "Error loading logs: ${e.message}\n\n" +
                                "Note: Some devices restrict logcat access.\n\n" +
                                "Tip: You can still select and copy any visible text."
                    }
                }
            }
        }.start()
    }
}
