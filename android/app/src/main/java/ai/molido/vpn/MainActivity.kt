package ai.molido.vpn

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            text = "MolidoVPN AI\n\nInitial engineering baseline\nVPN connection: Not Implemented"
            textSize = 20f
            setPadding(32, 48, 32, 32)
        }
        setContentView(status)
    }
}
