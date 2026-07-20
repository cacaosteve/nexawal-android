package com.nexatrode.nexawal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nexatrode.nexawal.ui.AppScaffold
import com.nexatrode.nexawal.ui.WalletCreationScreen
import com.nexatrode.nexawal.ui.theme.NexawalTheme

class MainActivity : ComponentActivity() {
    private lateinit var walletManager: WalletManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        walletManager = WalletManager(applicationContext)

        enableEdgeToEdge()
        setContent {
            NexawalTheme {
                val state by walletManager.state.collectAsState()

                LaunchedEffect(Unit) {
                    walletManager.loadVersion()

                    // Load persisted app settings (e.g., node URL) before loading/opening any stored wallet.
                    walletManager.loadSettingsOnLaunch()

                    // If a stored wallet exists, open it and start refresh automatically.
                    // When enabled, require device authentication first.
                    val hasStoredWallet = runCatching { walletManager.hasStoredWallet() }.getOrDefault(false)
                    if (hasStoredWallet) {
                        val shouldRequireAuth = MoneroConfig.requireDeviceAuth(applicationContext)
                        val unlocked = if (!shouldRequireAuth || !DeviceAuthGate.isAvailable(applicationContext)) {
                            true
                        } else {
                            runCatching {
                                DeviceAuthGate.authenticate(
                                    activity = this@MainActivity,
                                    title = "Unlock wallet",
                                    subtitle = "Authenticate to open the stored Monero wallet"
                                )
                            }.isSuccess
                        }

                        if (unlocked) {
                            val loaded = walletManager.loadStoredWalletOnLaunch()
                            if (loaded) {
                                walletManager.refreshWalletInBackground()
                            }
                        }
                    }
                }

                // iOS parity:
                // - If a wallet is open, show the main tab UI.
                // - Otherwise, show the wallet creation/import flow (seed paste view).
                if (state.walletId != null) {
                    AppScaffold(walletManager = walletManager)
                } else {
                    WalletCreationScreen(walletManager = walletManager)
                }
            }
        }
    }
}
