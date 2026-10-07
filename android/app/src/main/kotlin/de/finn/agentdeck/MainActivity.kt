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
import androidx.lifecycle.lifecycleScope
import de.finn.agentdeck.update.UpdateInstaller
import kotlinx.coroutines.launch
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
    private var awaitingInstallPermission = false
    private var permissionFileName: String? = null
    private var installing = false
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (awaitingInstallPermission) {
            awaitingInstallPermission = false
            if (packageManager.canRequestPackageInstalls()) installUpdate(permissionFileName)
            else graph.updates.installerMessage("Installation permission was not granted. Tap Install update when you're ready to allow it.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        awaitingInstallPermission = savedInstanceState?.getBoolean("awaitingInstallPermission") ?: false
        permissionFileName = savedInstanceState?.getString("permissionFileName")
        setContent {
            AgentDeckTheme {
                AppRoot(graph, vm, onScan = ::scanQr, onRequestPermission = ::requestNotificationPermission, onOpenNotificationSettings = ::openNotificationSettings, onInstallUpdate = { installUpdate() })
            }
        }
        if (savedInstanceState == null) {
            handleIntent(intent)
            // Ask once on first launch after pairing, Android 13+ only.
            if (graph.credentials.current() != null && !graph.notifier.canPost()) requestNotificationPermission()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("awaitingInstallPermission", awaitingInstallPermission)
        outState.putString("permissionFileName", permissionFileName)
        super.onSaveInstanceState(outState)
    }

    private fun installUpdate(expectedFileName: String? = null) {
        if (installing || awaitingInstallPermission) return
        installing = true
        lifecycleScope.launch {
            try {
                val file = graph.updates.installFile() ?: return@launch
                if (expectedFileName != null && file.name != expectedFileName) {
                    graph.updates.installerMessage("The download changed while Android settings were open. Tap Install update for this download.")
                    return@launch
                }
                if (!packageManager.canRequestPackageInstalls()) {
                    awaitingInstallPermission = true
                    permissionFileName = file.name
                    graph.updates.installerMessage("Allow Agent Deck to install apps on the next screen. Your verified download is saved.")
                    installPermission.launch(UpdateInstaller.permissionIntent(this@MainActivity))
                } else {
                    startActivity(UpdateInstaller.installIntent(this@MainActivity, file))
                    graph.updates.installerMessage("Android's installer is open. Confirm the update there, or tap Install update again if you cancelled.")
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                awaitingInstallPermission = false
                graph.updates.installerMessage("Android could not open the installer. Tap Install update to retry.")
            } finally { installing = false }
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
                val host = intent.getStringExtra(EXTRA_HOST)
                val device = intent.getStringExtra(EXTRA_DEVICE)
                // Legacy opens cannot prove their host; open the list instead of a wrong chat.
                if (host == null || device == null || graph.credentials.pairing(host, device) == null) {
                    vm.select(null)
                    return
                }
                vm.selectHost(host)
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
        const val EXTRA_HOST = "host"
        const val EXTRA_DEVICE = "device_id"
        const val EXTRA_SESSION_ID = "session_id"
    }
}
