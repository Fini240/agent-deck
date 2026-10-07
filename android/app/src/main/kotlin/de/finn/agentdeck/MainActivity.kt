package de.finn.agentdeck

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import de.finn.agentdeck.core.push.NotificationPlanner
import de.finn.agentdeck.ui.AppRoot
import de.finn.agentdeck.ui.MainViewModel
import de.finn.agentdeck.ui.theme.AgentDeckTheme

class MainActivity : ComponentActivity() {
    private val graph by lazy { (application as AgentDeckApplication).graph }
    private val vm: MainViewModel by viewModels {
        viewModelFactory { initializer { MainViewModel(graph, getSharedPreferences("ui", Context.MODE_PRIVATE)) } }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onPermissionResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AgentDeckTheme {
                AppRoot(graph, vm, onScan = ::scanQr, onRequestPermission = ::requestNotificationPermission, onOpenNotificationSettings = ::openNotificationSettings)
            }
        }
        if (savedInstanceState == null) {
            handleIntent(intent)
            // Ask once on first launch after pairing, Android 13+ only.
            if (graph.credentials.current() != null && !graph.notifier.canPost()) requestNotificationPermission()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when {
            intent == null -> Unit
            intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "agentdeck" -> vm.onPairingLink(intent.dataString.orEmpty())
            intent.action == ACTION_OPEN_SESSION -> intent.getStringExtra(EXTRA_SESSION_ID)?.let { id ->
                // The encrypted test push uses a synthetic session id; open the list instead.
                vm.select(id.takeUnless { it == NotificationPlanner.TEST_SESSION_ID })
            }
        }
    }

    private fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { code -> code.rawValue?.let(vm::onPairingLink) ?: vm.onScanError("The QR code was empty.") }
            .addOnFailureListener { e -> vm.onScanError("Scanner unavailable (${e.message}). Enter the address and code manually.") }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    companion object {
        const val ACTION_OPEN_SESSION = "de.finn.agentdeck.action.OPEN_SESSION"
        const val EXTRA_SESSION_ID = "session_id"
    }
}
