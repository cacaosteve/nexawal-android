package com.nexatrode.nexawal.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nexatrode.nexawal.DeviceAuthGate
import com.nexatrode.nexawal.MoneroConfig
import com.nexatrode.nexawal.WalletManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Must be top-level in Kotlin (local enums are not allowed).
private enum class WalletSetupMode { CREATE, IMPORT }

/**
 * WalletCreationScreen
 *
 * Android equivalent of iOS `WalletCreationView`:
 * - segmented mode: Create (fast) vs Import
 * - mnemonic text area (paste)
 * - restore height:
 *    - Create: shows suggested fast height (tip - 10) when available, user input hidden
 *    - Import: editable restore height field with tips/warnings
 * - mainnet toggle (kept for parity; you can keep it always true for now)
 * - device-auth opt-in during create/import
 * - single-wallet UX: if a wallet exists on device, confirm before replacing
 *
 * Notes:
 * - Suggested restore height uses a very small HTTP call to the configured node's `/get_info`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletCreationScreen(
    walletManager: WalletManager,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val state by walletManager.state.collectAsState()
    val scroll = rememberScrollState()
    val context = LocalContext.current
    val classicUI = remember { MoneroConfig.isClassicUIEnabled(context) }
    val palette = rememberNexaPalette(classicUI)
    val neon = palette.classic

    // UI state
    val modeIndex = remember { mutableIntStateOf(1) } // default to IMPORT like iOS
    val setupMode: WalletSetupMode = if (modeIndex.intValue == 0) WalletSetupMode.CREATE else WalletSetupMode.IMPORT

    val mnemonicInput = remember { mutableStateOf("") }
    val restoreHeightInput = remember { mutableStateOf("0") }
    val isMainnet = remember { mutableStateOf(true) }
    val requireDeviceAuth = remember { mutableStateOf(MoneroConfig.requireDeviceAuth(context)) }

    val isLoading = remember { mutableStateOf(false) }
    val errorText = remember { mutableStateOf<String?>(null) }

    // Replace confirm UX (single wallet slot)
    val hasStoredWallet = remember { mutableStateOf<Boolean?>(null) }
    val showReplaceConfirm = remember { mutableStateOf(false) }

    // Suggested restore height (create mode only)
    val suggestedRestoreHeight = remember { mutableStateOf<Long?>(null) }
    val isFetchingSuggestedHeight = remember { mutableStateOf(false) }
    val suggestedHeightError = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // Authoritative: check persisted wallet presence (metadata exists)
        hasStoredWallet.value = runCatching { walletManager.hasStoredWallet() }.getOrNull()
        // Fetch suggested restore height for create mode (best-effort)
        refreshSuggestedRestoreHeightIfNeeded(
            setupMode = setupMode,
            isMainnet = isMainnet.value,
            nodeUrl = walletManager.currentNodeUrl(),
            isFetching = isFetchingSuggestedHeight,
            suggestedHeight = suggestedRestoreHeight,
            suggestedError = suggestedHeightError,
        )
    }

    LaunchedEffect(setupMode, isMainnet.value) {
        refreshSuggestedRestoreHeightIfNeeded(
            setupMode = setupMode,
            isMainnet = isMainnet.value,
            nodeUrl = walletManager.currentNodeUrl(),
            isFetching = isFetchingSuggestedHeight,
            suggestedHeight = suggestedRestoreHeight,
            suggestedError = suggestedHeightError,
        )
    }

    fun effectiveRestoreHeight(): Long {
        val raw = restoreHeightInput.value.trim().toLongOrNull() ?: 0L
        return if (setupMode == WalletSetupMode.CREATE && raw == 0L) {
            // Feather-style optimization in create mode: if user leaves 0, use tip-10 suggestion if available
            suggestedRestoreHeight.value ?: 0L
        } else {
            raw
        }
    }

    fun canSubmit(): Boolean = mnemonicInput.value.trim().isNotEmpty() && !isLoading.value

    fun submit(replaceExisting: Boolean) {
        errorText.value = null
        isLoading.value = true

        scope.launch {
            try {
                MoneroConfig.setRequireDeviceAuth(
                    context,
                    requireDeviceAuth.value && DeviceAuthGate.isAvailable(context)
                )

                val walletId = walletManager.defaultWalletId()
                val nodeUrl = walletManager.currentNodeUrl()

                walletManager.openWalletFromMnemonic(
                    walletId = walletId,
                    mnemonic = mnemonicInput.value,
                    restoreHeight = effectiveRestoreHeight(),
                    nodeUrl = nodeUrl,
                    mainnet = isMainnet.value,
                    persist = true,
                    replaceExisting = replaceExisting,
                )

                // iOS parity: after create/import, start refresh immediately BUT do not await it here.
                // Use the manager-owned scope so the refresh survives removal of the setup screen.
                walletManager.refreshWalletInBackground()

                // After importing/replacing, refresh persisted-wallet flag
                hasStoredWallet.value = runCatching { walletManager.hasStoredWallet() }.getOrNull()
            } catch (t: Throwable) {
                errorText.value = t.message ?: t.javaClass.simpleName
            } finally {
                isLoading.value = false
            }
        }
    }

    fun unlockStoredWallet() {
        errorText.value = null
        isLoading.value = true

        scope.launch {
            try {
                if (MoneroConfig.requireDeviceAuth(context) && DeviceAuthGate.isAvailable(context)) {
                    val activity = context as? ComponentActivity
                        ?: throw IllegalStateException("Device authentication requires an activity context")
                    DeviceAuthGate.authenticate(
                        activity = activity,
                        title = "Unlock wallet",
                        subtitle = "Authenticate to open the stored Monero wallet"
                    )
                }

                val loaded = walletManager.loadStoredWalletOnLaunch()
                if (!loaded) {
                    errorText.value = "No stored wallet was found"
                } else {
                    walletManager.refreshWalletInBackground()
                }
            } catch (t: Throwable) {
                errorText.value = t.message ?: t.javaClass.simpleName
            } finally {
                isLoading.value = false
            }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = palette.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (neon) "NEXAWAL" else "Create Wallet",
                        color = palette.primaryText,
                        fontFamily = if (neon) FontFamily.Monospace else FontFamily.Default,
                        fontWeight = if (neon) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = palette.background,
                    titleContentColor = palette.primaryText,
                ),
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(scroll)
        ) {
            Text(
                if (neon) "WALLET SETUP" else "Wallet Setup",
                color = palette.primaryText,
                fontWeight = FontWeight.Bold,
                fontFamily = if (neon) FontFamily.Monospace else FontFamily.Default,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Choose whether you’re creating a brand new wallet (fast sync) or importing an existing wallet " +
                    "(full scan unless you set a restore height).",
                color = palette.secondaryText,
            )

            if (hasStoredWallet.value == true) {
                Spacer(Modifier.height(12.dp))
                Text("A wallet is already stored on this device.", color = palette.secondaryText)
                Spacer(Modifier.height(8.dp))
                PrimaryActionButton(
                    text = if (isLoading.value) "Unlocking..." else "Unlock Existing Wallet",
                    onClick = { unlockStoredWallet() },
                    palette = palette,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading.value,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Segmented control: Create vs Import
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = modeIndex.intValue == 0,
                    onClick = { modeIndex.intValue = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    label = { Text("Create new wallet (fast)") }
                )
                SegmentedButton(
                    selected = modeIndex.intValue == 1,
                    onClick = { modeIndex.intValue = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    label = { Text("Import existing wallet") }
                )
            }

            Spacer(Modifier.height(12.dp))

            Text("Mnemonic (paste):", color = palette.primaryText)
            OutlinedTextField(
                value = mnemonicInput.value,
                onValueChange = { mnemonicInput.value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                placeholder = { Text("Paste 25-word mnemonic…", color = palette.secondaryText) },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    color = palette.primaryText,
                ),
                singleLine = false,
                colors = nexaFieldColors(palette),
            )

            Spacer(Modifier.height(12.dp))

            when (setupMode) {
                WalletSetupMode.CREATE -> {
                    Text("Starting height (fast):", color = palette.primaryText)
                    Spacer(Modifier.height(6.dp))

                    when {
                        isFetchingSuggestedHeight.value -> {
                            Text("Fetching from node…", color = palette.secondaryText)
                            Spacer(Modifier.height(8.dp))
                            CircularProgressIndicator()
                        }
                        suggestedRestoreHeight.value != null -> {
                            Text("Starting height (fast): ${suggestedRestoreHeight.value} (node target_height − 10)", color = palette.primaryText)
                        }
                        else -> {
                            Text("Starting height (fast): unavailable (will use 0)", color = palette.secondaryText)
                        }
                    }

                    suggestedHeightError.value?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = palette.danger)
                    }
                }

                WalletSetupMode.IMPORT -> {
                    Text("Restore Height:", color = palette.primaryText)
                    Spacer(Modifier.height(6.dp))

                    OutlinedTextField(
                        value = restoreHeightInput.value,
                        onValueChange = { restoreHeightInput.value = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("0", color = palette.secondaryText) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = nexaFieldColors(palette),
                    )

                    Spacer(Modifier.height(6.dp))

                    val height = restoreHeightInput.value.trim().toLongOrNull() ?: 0L
                    if (height == 0L) {
                        Text("Tip: 0 scans the full chain history. This is the safest option if you’re unsure, but it can take longer to sync.", color = palette.secondaryText)
                    } else {
                        Text("Warning: If you set a restore height after your first transaction, older funds will not appear until you rescan from an earlier height.", color = palette.secondaryText)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Mainnet toggle (parity)
            RowSwitch(label = "Mainnet", state = isMainnet, palette = palette)

            Spacer(Modifier.height(12.dp))

            RowSwitch(
                label = "Require device auth",
                state = requireDeviceAuth,
                enabled = DeviceAuthGate.isAvailable(context),
                palette = palette,
            )

            Spacer(Modifier.height(6.dp))

            if (DeviceAuthGate.isAvailable(context)) {
                Text(
                    "When enabled, opening the stored wallet and sending funds will require device authentication.",
                    color = palette.secondaryText,
                )
            } else {
                Text(
                    "Biometric or device credential authentication is not currently available on this device.",
                    color = palette.secondaryText,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Submit button (Import / Create)
            val primaryLabel = if (setupMode == WalletSetupMode.IMPORT) "Import Wallet" else "Create Wallet"
            PrimaryActionButton(
                text = if (isLoading.value) "${primaryLabel}…" else primaryLabel,
                enabled = canSubmit(),
                onClick = {
                    val stored = hasStoredWallet.value == true
                    if (stored) {
                        showReplaceConfirm.value = true
                    } else {
                        submit(replaceExisting = false)
                    }
                },
                palette = palette,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            // Error section
            val mergedError = errorText.value ?: state.lastError
            if (mergedError != null) {
                Text("Error: $mergedError", color = palette.danger)
                Spacer(Modifier.height(12.dp))
            }

            // Info section (iOS parity)
            Text(if (neon) "INFO" else "Info", color = palette.primaryText)
            Spacer(Modifier.height(6.dp))
            Text("WalletCore Version: ${state.version ?: "(unknown)"}", color = palette.secondaryText)
            Text("Node Address: ${walletManager.currentNodeUrl()}", color = palette.secondaryText)

            Spacer(Modifier.height(24.dp))
        }

        if (showReplaceConfirm.value) {
            ReplaceWalletConfirmDialog(
                palette = palette,
                onCancel = { showReplaceConfirm.value = false },
                onReplace = {
                    showReplaceConfirm.value = false
                    submit(replaceExisting = true)
                }
            )
        }
    }
}

@Composable
private fun ReplaceWalletConfirmDialog(
    palette: NexaPalette,
    onCancel: () -> Unit,
    onReplace: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Replace existing wallet?") },
        text = {
            Text(
                "This will replace the existing wallet on this device.\n\n" +
                    "If you continue, the currently stored mnemonic and scan state will be removed. " +
                    "Make sure you have your mnemonic backed up before proceeding."
            )
        },
        confirmButton = {
            PrimaryActionButton(text = "Replace", onClick = onReplace, palette = palette)
        },
        dismissButton = {
            SecondaryActionButton(text = "Cancel", onClick = onCancel, palette = palette)
        }
    )
}

@Composable
private fun RowSwitch(
    label: String,
    state: MutableState<Boolean>,
    enabled: Boolean = true,
    palette: NexaPalette,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = palette.primaryText)
        Switch(
            checked = state.value,
            onCheckedChange = { state.value = it },
            enabled = enabled,
            colors = nexaSwitchColors(palette),
        )
    }
}

private suspend fun refreshSuggestedRestoreHeightIfNeeded(
    setupMode: WalletSetupMode,
    isMainnet: Boolean,
    nodeUrl: String,
    isFetching: MutableState<Boolean>,
    suggestedHeight: MutableState<Long?>,
    suggestedError: MutableState<String?>,
) {
    // Only for create mode
    if (setupMode != WalletSetupMode.CREATE) {
        suggestedHeight.value = null
        suggestedError.value = null
        isFetching.value = false
        return
    }

    isFetching.value = true
    suggestedError.value = null
    suggestedHeight.value = null

    // Minimal /get_info call (mirrors iOS MoneroDaemonClient.getInfo usage).
    // We only need target_height to suggest restoreHeight = target_height - 10.
    try {
        val tip = fetchMoneroTargetHeight(nodeUrl)
        val suggested = if (tip > 10L) tip - 10L else 0L
        suggestedHeight.value = suggested
    } catch (t: Throwable) {
        // Non-fatal: this is only used to suggest a fast height.
        suggestedHeight.value = null
        suggestedError.value = "Couldn’t fetch a fast restore height from the node. Leaving restore height as 0."
    } finally {
        isFetching.value = false
    }
}

/**
 * Fetch `target_height` from a monerod daemon using `/get_info`.
 *
 * This intentionally avoids introducing a full HTTP client dependency (Retrofit/OkHttp) for bring-up.
 */
private suspend fun fetchMoneroTargetHeight(baseUrl: String): Long {
    return withContext(Dispatchers.IO) {
        val url = java.net.URL(baseUrl.trimEnd('/') + "/get_info")
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
            doInput = true
        }

        conn.connect()
        val code = conn.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("get_info failed: HTTP $code")
        }

        val body = conn.inputStream.bufferedReader().use { it.readText() }

        // Minimal parse: find `"target_height": <number>`
        val key = "\"target_height\""
        val idx = body.indexOf(key)
        if (idx < 0) throw IllegalStateException("target_height not found")
        val colon = body.indexOf(':', idx)
        if (colon < 0) throw IllegalStateException("target_height parse error")
        val end = body.indexOfAny(charArrayOf(',', '\n', '\r', '}'), startIndex = colon + 1).let { if (it < 0) body.length else it }
        body.substring(colon + 1, end).trim().toLong()
    }
}
