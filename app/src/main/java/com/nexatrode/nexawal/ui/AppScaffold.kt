package com.nexatrode.nexawal.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nexatrode.nexawal.MoneroQr
import com.nexatrode.nexawal.SendJson
import com.nexatrode.nexawal.TimeFormat
import com.nexatrode.nexawal.Transfer
import com.nexatrode.nexawal.WalletManager
import com.nexatrode.nexawal.XmrFormat
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * Root application scaffold with bottom-tab navigation.
 */
@Composable
fun AppScaffold(
    walletManager: WalletManager,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val items = listOf(
        BottomNavItem.Wallet,
        BottomNavItem.Send,
        BottomNavItem.Settings,
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                items.forEach { item ->
                    val selected = currentDestination
                        ?.hierarchy
                        ?.any { it.route == item.route } == true

                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label
                            )
                        },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = BottomNavItem.Wallet.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(BottomNavItem.Wallet.route) {
                WalletScreen(walletManager = walletManager)
            }
            composable(BottomNavItem.Send.route) {
                SendScreen(walletManager = walletManager)
            }
            composable(BottomNavItem.Settings.route) {
                SettingsScreen(walletManager = walletManager)
            }
        }
    }
}

private sealed class BottomNavItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    data object Wallet : BottomNavItem(
        route = "wallet",
        label = "Wallet",
        icon = Icons.Filled.Home
    )

    data object Send : BottomNavItem(
        route = "send",
        label = "Send",
        icon = Icons.AutoMirrored.Filled.Send
    )

    data object Settings : BottomNavItem(
        route = "settings",
        label = "Settings",
        icon = Icons.Filled.Settings
    )
}

/**
 * Wallet screen refactored to mirror iOS WalletView layout:
 * - Balance card (Total + Unlocked)
 * - Address card (monospace, selectable)
 * - Sync status (heights + progress)
 * - Transactions list (tap row -> details dialog)
 * - Actions row (Refresh/Cancel or Send/Receive)
 *
 * NOTE: "Receive" is not implemented yet; for parity we show a placeholder action.
 */
@Composable
private fun WalletScreen(walletManager: WalletManager) {
    val scope = rememberCoroutineScope()
    val state by walletManager.state.collectAsState()
    val scroll = rememberScrollState()
    val clipboard = ClipboardCompat.current()
    val context = LocalContext.current

    var errorText by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }

    var selectedTransfer by remember { mutableStateOf<Transfer?>(null) }
    var showTransferDetails by remember { mutableStateOf(false) }

    var showReceiveDialog by remember { mutableStateOf(false) }

    // Receive UI inputs (mirror iOS ReceiveView)
    var receiveAmountXmr by remember { mutableStateOf("") }
    var receiveDescription by remember { mutableStateOf("") }

    val mergedError = errorText ?: state.lastError

    val totalXmr = state.balance?.totalXmr ?: "0.000000000000"
    val unlockedXmr = state.balance?.unlockedXmr ?: "0.000000000000"
    val walletAddress = state.walletAddress ?: "(unknown — open wallet to derive)"

    val st = state.syncStatus
    val chainHeight = st?.chainHeight ?: 0L
    val lastScanned = st?.lastScanned ?: 0L
    val restoreHeight = st?.restoreHeight ?: 0L
    val remainingBlocks = (chainHeight - lastScanned).coerceAtLeast(0L)
    val isSynced = chainHeight > 0L && lastScanned >= chainHeight

    // Very rough progress; iOS has a more nuanced model but this mirrors the idea.
    val progress = if (chainHeight <= 0L) 0f else (lastScanned.toDouble() / chainHeight.toDouble()).coerceIn(0.0, 1.0).toFloat()

    // Sort transfers like iOS: pending first, then height desc, then timestamp desc.
    val transfersSorted: List<Transfer> = remember(state.transfers) {
        state.transfers.sortedWith { a, b ->
            // Pending first
            if (a.pending != b.pending) return@sortedWith if (a.pending) -1 else 1
            // Height desc (null treated as 0)
            val ah = a.height ?: 0L
            val bh = b.height ?: 0L
            if (ah != bh) return@sortedWith if (ah > bh) -1 else 1
            // Timestamp desc (null treated as 0)
            val at = a.timestamp ?: 0L
            val bt = b.timestamp ?: 0L
            if (at != bt) return@sortedWith if (at > bt) -1 else 1
            // Stable tie-breaker
            a.txid.compareTo(b.txid)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp)
    ) {
        // Balance card (like iOS)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF2F2F7))
                .padding(16.dp)
        ) {
            Text("Total Balance", color = Color.Gray)
            Spacer(Modifier.height(6.dp))
            Text(
                totalXmr,
                fontFamily = FontFamily.Monospace
            )

            Spacer(Modifier.height(12.dp))

            Text("Unlocked Balance", color = Color.Gray)
            Spacer(Modifier.height(6.dp))
            Text(
                unlockedXmr,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF007AFF)
            )
        }

        Spacer(Modifier.height(16.dp))

        // Address card (like iOS)
        Text("Wallet Address")
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Text(
                walletAddress,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF2F2F7))
                    .padding(12.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                errorText = null
                if (state.walletAddress.isNullOrBlank()) {
                    statusText = "No address available yet"
                } else {
                    scope.launch {
                        ClipboardCompat.setText(clipboard, state.walletAddress!!)
                    }
                    statusText = "Copied address"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Copy Address")
        }

        Spacer(Modifier.height(16.dp))

        // Sync Status
        Text("Sync Status", color = Color.Gray)
        Spacer(Modifier.height(8.dp))

        KeyValueRow("Chain Height", chainHeight.toString())
        KeyValueRow("Last Scanned", lastScanned.toString())
        if (!isSynced) {
            KeyValueRow("Remaining Blocks", remainingBlocks.toString())
        }
        KeyValueRow("Target Height", chainHeight.toString())
        KeyValueRow("Restore Height", restoreHeight.toString())

        Spacer(Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = ProgressIndicatorDefaults.linearColor,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (isSynced) "Wallet is fully synced"
            else if (chainHeight == 0L || lastScanned == restoreHeight) "Initializing scan…"
            else "Syncing… $remainingBlocks blocks remaining",
            color = Color.Gray
        )

        Spacer(Modifier.height(16.dp))

        // Transactions list (like iOS)
        Text("Transactions", color = Color.Gray)
        Spacer(Modifier.height(8.dp))

        if (transfersSorted.isEmpty()) {
            Text("No transactions yet.", color = Color.Gray)
        } else {
            transfersSorted.forEach { t ->
                TransferRow(
                    t = t,
                    onClick = {
                        selectedTransfer = t
                        showTransferDetails = true
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Actions row (iOS-like)
        if (state.refreshInProgress) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { /* no-op while refreshing */ },
                    enabled = false,
                    modifier = Modifier.weight(1f)
                ) { Text("Refreshing…") }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = {
                        walletManager.cancelRefresh()
                        statusText = "Cancel requested"
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel") }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        errorText = null
                        scope.launch {
                            try {
                                walletManager.refreshWallet()
                                walletManager.refreshWalletDataSnapshots()
                                statusText = "Refreshed"
                            } catch (t: Throwable) {
                                errorText = t.message ?: t.javaClass.simpleName
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Refresh Wallet") }

                Spacer(Modifier.width(12.dp))

                Button(
                    onClick = {
                        // We don't have modal navigation yet; user can go to Send tab.
                        statusText = "Use Send tab"
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Send") }

                Spacer(Modifier.width(12.dp))

                Button(
                    onClick = {
                        errorText = null
                        showReceiveDialog = true
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Receive") }
            }
        }

        statusText?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Color.Gray)
        }

        mergedError?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                color = Color(0xFFB00020),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x1AB00020))
                    .padding(12.dp)
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showTransferDetails && selectedTransfer != null) {
        TransferDetailsDialog(
            t = selectedTransfer!!,
            onDismiss = {
                showTransferDetails = false
                selectedTransfer = null
            }
        )
    }

    if (showReceiveDialog) {
        val addr = state.walletAddress?.trim().orEmpty()
        val uri = if (addr.isNotEmpty()) {
            MoneroQr.buildUri(
                address = addr,
                amountXmr = receiveAmountXmr.takeIf { it.trim().isNotEmpty() }?.trim(),
                description = receiveDescription.takeIf { it.trim().isNotEmpty() }?.trim(),
            )
        } else {
            ""
        }

        val qrBitmap = runCatching {
            if (uri.isNotEmpty()) MoneroQr.qrBitmap(uri, sizePx = 640) else null
        }.getOrNull()

        AlertDialog(
            onDismissRequest = { showReceiveDialog = false },
            title = { Text("Receive") },
            text = {
                Column {
                    Text("Address", color = Color.Gray)
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Text(
                            if (addr.isNotEmpty()) addr else "(no address yet)",
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Text("Amount (XMR) (optional)", color = Color.Gray)
                    OutlinedTextField(
                        value = receiveAmountXmr,
                        onValueChange = { receiveAmountXmr = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("0.0") }
                    )

                    Spacer(Modifier.height(8.dp))

                    Text("Description (optional)", color = Color.Gray)
                    OutlinedTextField(
                        value = receiveDescription,
                        onValueChange = { receiveDescription = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("What is this for?") }
                    )

                    Spacer(Modifier.height(12.dp))

                    Text("Monero URI", color = Color.Gray)
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Text(
                            if (uri.isNotEmpty()) uri else "(unavailable)",
                            fontFamily = FontFamily.Monospace,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Monero payment request QR"
                        )
                    } else {
                        Text("QR unavailable", color = Color.Gray)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    errorText = null
                    if (addr.isEmpty()) {
                        statusText = "No address available yet"
                        showReceiveDialog = false
                        return@Button
                    }
                    // Copy URI (matches iOS share/copy friendliness)
                    scope.launch {
                        ClipboardCompat.setText(clipboard, if (uri.isNotEmpty()) uri else addr)
                    }
                    statusText = "Copied"
                    showReceiveDialog = false
                }) {
                    Text("Copy")
                }
            },
            dismissButton = {
                Button(onClick = {
                    errorText = null
                    if (uri.isEmpty() || qrBitmap == null) {
                        statusText = "Nothing to share yet"
                        showReceiveDialog = false
                        return@Button
                    }

                    runCatching {
                        // Write QR bitmap to cache and share it.
                        val dir = File(context.cacheDir, "shares").apply { mkdirs() }
                        val outFile = File(dir, "monero_receive_qr.png")
                        FileOutputStream(outFile).use { fos ->
                            qrBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
                        }

                        val uriFile = FileProvider.getUriForFile(
                            context,
                            context.packageName + ".fileprovider",
                            outFile
                        )

                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uriFile)
                            putExtra(Intent.EXTRA_TEXT, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }

                        context.startActivity(Intent.createChooser(shareIntent, "Share"))
                    }.onFailure { t ->
                        statusText = "Share failed: ${t.message ?: t.javaClass.simpleName}"
                    }

                    showReceiveDialog = false
                }) {
                    Text("Share")
                }
            }
        )
    }
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TransferRow(
    t: Transfer,
    onClick: () -> Unit,
) {
    val direction = t.directionLabel()
    val amountColor = when (t.direction.lowercase()) {
        "in" -> Color(0xFF34C759) // green
        "out" -> Color(0xFFFF3B30) // red
        else -> Color.Unspecified
    }

    val relTime = TimeFormat.relative(t.timestamp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Row {
                    Text(direction, color = Color.Gray)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (t.pending) "Pending" else "${t.confirmations} conf",
                        color = Color.Gray
                    )
                }

                relTime?.let {
                    Text(it, color = Color.Gray)
                }

                Text(
                    t.txid,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column {
                Text(
                    t.amountXmr(),
                    fontFamily = FontFamily.Monospace,
                    color = amountColor
                )
                t.fee?.let {
                    Text(
                        "Fee ${XmrFormat.formatPiconeroAsXmr(it)}",
                        fontFamily = FontFamily.Monospace,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferDetailsDialog(
    t: Transfer,
    onDismiss: () -> Unit,
) {
    val absTime = TimeFormat.absolute(t.timestamp)
    val clipboard = ClipboardCompat.current()
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transaction") },
        text = {
            Column {
                Text("Type: ${t.directionLabel()}", fontFamily = FontFamily.Monospace)
                Text("Status: ${if (t.pending) "Pending" else "Confirmed"}", fontFamily = FontFamily.Monospace)
                Text("Amount: ${t.amountXmr()}", fontFamily = FontFamily.Monospace)
                t.fee?.let { Text("Fee: ${XmrFormat.formatPiconeroAsXmr(it)}", fontFamily = FontFamily.Monospace) }
                Spacer(Modifier.height(8.dp))
                Text("Height: ${t.height ?: "—"}", fontFamily = FontFamily.Monospace)
                Text("Confirmations: ${t.confirmations}", fontFamily = FontFamily.Monospace)
                Text("Time: ${absTime ?: "—"}", fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text("TXID:\n${t.txid}", fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    scope.launch {
                        ClipboardCompat.setText(clipboard, t.txid)
                    }
                }) {
                    Text("Copy TXID")
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Close") }
        }
    )
}

/**
 * Send screen: fee preview, send, and send max (sweep) using WalletManager.
 */
@Composable
private fun SendScreen(walletManager: WalletManager) {
    val scope = rememberCoroutineScope()
    val state by walletManager.state.collectAsState()

    var toAddress by remember { mutableStateOf("") }
    var amountXmrText by remember { mutableStateOf("") }
    var ringLenText by remember { mutableStateOf("16") }

    var feePreviewText by remember { mutableStateOf("(none)") }
    var sweepPreviewText by remember { mutableStateOf("(none)") }
    var lastActionText by remember { mutableStateOf("(idle)") }
    var errorText by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Send")
        Spacer(Modifier.height(8.dp))

        Text("walletId: ${state.walletId ?: "(none)"}")
        Text("nodeUrl: ${state.nodeUrl ?: walletManager.defaultNodeUrl()}")
        Spacer(Modifier.height(12.dp))

        Text("To address:")
        OutlinedTextField(
            value = toAddress,
            onValueChange = { toAddress = it },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        Text("Amount (XMR):")
        OutlinedTextField(
            value = amountXmrText,
            onValueChange = { amountXmrText = it },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        Text("Ring length:")
        OutlinedTextField(
            value = ringLenText,
            onValueChange = { ringLenText = it },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        )

        val mergedError = errorText ?: state.lastError
        if (mergedError != null) {
            Text("error: $mergedError")
            Spacer(Modifier.height(12.dp))
        }

        Button(onClick = {
            errorText = null
            scope.launch {
                try {
                    val wid = state.walletId ?: throw IllegalStateException("No wallet open")
                    val ringLen = ringLenText.toIntOrNull() ?: 16
                    val amountPiconero = parseXmrToPiconero(amountXmrText)

                    val fee = walletManager.previewFee(
                        destinations = listOf(
                            SendJson.Destination(
                                address = toAddress.trim(),
                                amount = amountPiconero
                            )
                        ),
                        ringLen = ringLen
                    )

                    feePreviewText = "fee=${fee.feeXmr} XMR"
                    lastActionText = "Fee preview ok (walletId=$wid)"
                } catch (t: Throwable) {
                    errorText = t.message ?: t.javaClass.simpleName
                }
            }
        }) {
            Text("Preview fee")
        }

        Spacer(Modifier.height(8.dp))
        Text("Fee preview: $feePreviewText")

        Spacer(Modifier.height(12.dp))

        Button(onClick = {
            errorText = null
            scope.launch {
                try {
                    val wid = state.walletId ?: throw IllegalStateException("No wallet open")
                    val ringLen = ringLenText.toIntOrNull() ?: 16
                    val amountPiconero = parseXmrToPiconero(amountXmrText)

                    val res = walletManager.send(
                        toAddress = toAddress.trim(),
                        amountPiconero = amountPiconero,
                        ringLen = ringLen
                    )

                    lastActionText =
                        "Sent tx=${res.txidShort()} fee=${res.feeXmr} XMR (walletId=$wid)"
                } catch (t: Throwable) {
                    errorText = t.message ?: t.javaClass.simpleName
                }
            }
        }) {
            Text("Send")
        }

        Spacer(Modifier.height(12.dp))

        Button(onClick = {
            errorText = null
            scope.launch {
                try {
                    val wid = state.walletId ?: throw IllegalStateException("No wallet open")
                    val ringLen = ringLenText.toIntOrNull() ?: 16

                    val prev = walletManager.previewSweep(
                        toAddress = toAddress.trim(),
                        ringLen = ringLen
                    )

                    sweepPreviewText =
                        "amount=${prev.amountXmr} XMR fee=${prev.feeXmr} XMR"
                    lastActionText = "Sweep preview ok (walletId=$wid)"
                } catch (t: Throwable) {
                    errorText = t.message ?: t.javaClass.simpleName
                }
            }
        }) {
            Text("Preview send max")
        }

        Spacer(Modifier.height(8.dp))
        Text("Send max preview: $sweepPreviewText")

        Spacer(Modifier.height(8.dp))

        Button(onClick = {
            errorText = null
            scope.launch {
                try {
                    val wid = state.walletId ?: throw IllegalStateException("No wallet open")
                    val ringLen = ringLenText.toIntOrNull() ?: 16

                    val res = walletManager.sweep(
                        toAddress = toAddress.trim(),
                        ringLen = ringLen
                    )

                    lastActionText =
                        "Swept tx=${res.txidShort()} amount=${res.amountXmr} XMR fee=${res.feeXmr} XMR (walletId=$wid)"
                } catch (t: Throwable) {
                    errorText = t.message ?: t.javaClass.simpleName
                }
            }
        }) {
            Text("Send max")
        }

        Spacer(Modifier.height(12.dp))
        Text(lastActionText)
    }
}

/**
 * Settings screen with editable node URL.
 */
@Composable
private fun SettingsScreen(walletManager: WalletManager) {
    val scope = rememberCoroutineScope()
    val state by walletManager.state.collectAsState()

    var nodeUrlInput by remember {
        mutableStateOf(state.nodeUrl ?: walletManager.defaultNodeUrl())
    }
    var statusText by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Settings")
        Spacer(Modifier.height(8.dp))

        Text("Node URL")
        OutlinedTextField(
            value = nodeUrlInput,
            onValueChange = { nodeUrlInput = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(walletManager.defaultNodeUrl()) }
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                statusText = null
                scope.launch {
                    try {
                        walletManager.setNodeUrl(nodeUrlInput)
                        statusText = "Saved node URL"
                    } catch (t: Throwable) {
                        statusText = "Failed to save: ${t.message ?: t.javaClass.simpleName}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save")
        }

        Spacer(Modifier.height(8.dp))

        Text("Current node: ${state.nodeUrl ?: walletManager.defaultNodeUrl()}")

        statusText?.let {
            Spacer(Modifier.height(8.dp))
            Text(it)
        }

        Spacer(Modifier.height(12.dp))
        Text("TODO: gap/account gap + network policy (mainnet-only now)")
    }
}

/**
 * Parse a decimal XMR string into piconero (1e12).
 */
private fun parseXmrToPiconero(xmr: String): Long {
    val s = xmr.trim()
    require(s.isNotEmpty()) { "amount must not be empty" }
    val parts = s.split('.', limit = 2)
    val whole = parts[0].ifEmpty { "0" }.toLong()
    val frac = if (parts.size == 2) parts[1] else ""
    require(frac.length <= 12) { "too many decimal places (max 12)" }
    val fracPadded = frac.padEnd(12, '0')
    val fracVal = if (fracPadded.isEmpty()) 0L else fracPadded.toLong()
    return whole * 1_000_000_000_000L + fracVal
}
