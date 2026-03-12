package com.nexatrode.nexawal.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nexatrode.nexawal.MoneroConfig
import com.nexatrode.nexawal.MoneroQr
import com.nexatrode.nexawal.SendJson
import com.nexatrode.nexawal.TimeFormat
import com.nexatrode.nexawal.Transfer
import com.nexatrode.nexawal.WalletManager
import com.nexatrode.nexawal.XmrFormat
import com.nexatrode.nexawal.walletcore.WalletCore
import kotlinx.coroutines.delay
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

    // iOS-like incremental UI updates:
    // While a refresh is running, periodically refresh balance/transfers so the UI updates
    // without waiting for the refresh to fully complete.
    //
    // Tuning:
    // - Balance is cheap to query; update frequently.
    // - Transfers require JSON generation + parsing; update less frequently to reduce UI/jank.
    LaunchedEffect(state.refreshInProgress, state.walletId) {
        if (!state.refreshInProgress) return@LaunchedEffect
        val walletId = state.walletId ?: return@LaunchedEffect

        var lastTransfersRefreshAtMs = 0L
        val balanceIntervalMs = 2_000L
        val transfersIntervalMs = 8_000L

        while (state.refreshInProgress) {
            val now = System.currentTimeMillis()

            // Best-effort: don't crash UI if these throw.
            runCatching { walletManager.refreshBalanceSnapshot() }

            if (now - lastTransfersRefreshAtMs >= transfersIntervalMs) {
                runCatching { walletManager.refreshTransfersSnapshot() }
                lastTransfersRefreshAtMs = now
            }

            delay(balanceIntervalMs)
        }
    }

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

    // Synced display logic fix:
    // The core initializes chainHeight to restoreHeight on open/import (before contacting the daemon),
    // which can make the UI look "fully synced" with 0 XMR. Only claim synced once the tip/target
    // is actually known.
    //
    // Prefer a stable target captured at refresh start; otherwise only treat chainHeight as usable
    // if it is strictly greater than restoreHeight (meaning we have learned a real daemon height).
    val tipKnown = chainHeight > restoreHeight
    val targetHeight = when {
        state.refreshTargetHeight != null -> state.refreshTargetHeight!!
        tipKnown -> chainHeight
        else -> 0L
    }

    val remainingBlocks = if (targetHeight > 0L) (targetHeight - lastScanned).coerceAtLeast(0L) else 0L
    val isSynced = targetHeight > 0L && lastScanned >= targetHeight

    // iOS parity: show scan tuning values (persisted) if available.
    // These are populated by WalletManager at refresh start.
    val accountGap = state.accountGap
    val gapLimit = state.gapLimit

    // iOS-like blocks/sec:
    // - compute instantaneous rate from lastScanned deltas
    // - smooth via exponential moving average (EMA) to avoid spiky UI
    var lastRateSampleAtMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastRateSampleScanned by remember { mutableStateOf(lastScanned) }
    var blocksPerSecInstant by remember { mutableStateOf(0.0) }
    var blocksPerSecSmoothed by remember { mutableStateOf(0.0) }

    LaunchedEffect(state.refreshInProgress, lastScanned) {
        val now = System.currentTimeMillis()
        val dtMs = (now - lastRateSampleAtMs).coerceAtLeast(1L)
        val dScanned = (lastScanned - lastRateSampleScanned).coerceAtLeast(0L)

        blocksPerSecInstant = (dScanned.toDouble() * 1000.0) / dtMs.toDouble()

        // EMA smoothing:
        // Higher alpha -> reacts faster; lower alpha -> smoother.
        val alpha = 0.25
        blocksPerSecSmoothed = if (blocksPerSecSmoothed <= 0.0) {
            blocksPerSecInstant
        } else {
            (alpha * blocksPerSecInstant) + ((1.0 - alpha) * blocksPerSecSmoothed)
        }

        lastRateSampleAtMs = now
        lastRateSampleScanned = lastScanned
    }

    // iOS-like theme-aware colors (approximate).
    // We keep iOS "System Blue" consistent and vary backgrounds/secondary text with system theme.
    val dark = isSystemInDarkTheme()
    val iosBlue = Color(0xFF007AFF)

    // iOS grouped background approximations:
    // - light: #F2F2F7
    // - dark:  near iOS system grouped background
    val iosGroupedBg = if (dark) Color(0xFF000000) else Color(0xFFF2F2F7)
    val iosCardBg = if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val iosSecondary = if (dark) Color(0xFF8E8E93) else Color(0xFF6D6D72)
    val iosSeparator = if (dark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)
    val iosPrimaryText = if (dark) Color(0xFFFFFFFF) else Color(0xFF000000)

    // Match iOS WalletView sizing:
    // - Total: 36pt bold
    // - Unlocked: 24pt semibold
    val totalAmountSp = 36.sp
    val unlockedAmountSp = 24.sp
    val suffixSp = 14.sp

    // Very rough progress; iOS uses a stable target height snapshot.
    val progress = if (targetHeight <= 0L) 0f else (lastScanned.toDouble() / targetHeight.toDouble()).coerceIn(0.0, 1.0).toFloat()

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
            .background(iosGroupedBg)
            .verticalScroll(scroll)
            .padding(16.dp)
    ) {
        // Balance card (iOS-like, theme-aware)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = iosCardBg,
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 1.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("Total Balance", color = iosSecondary)
                Spacer(Modifier.height(6.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = totalAmountSp,
                                color = iosPrimaryText
                            )
                        ) {
                            append(totalXmr)
                        }
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Normal,
                                fontSize = suffixSp,
                                color = iosSecondary
                            )
                        ) {
                            append(" XMR")
                        }
                    }
                )

                Spacer(Modifier.height(14.dp))

                Text("Unlocked Balance", color = iosSecondary)
                Spacer(Modifier.height(6.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = unlockedAmountSp,
                                color = iosBlue
                            )
                        ) {
                            append(unlockedXmr)
                        }
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Normal,
                                fontSize = suffixSp,
                                color = iosSecondary
                            )
                        ) {
                            append(" XMR")
                        }
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Address card (iOS-like, theme-aware)
        Text("Wallet Address", color = iosSecondary)
        Spacer(Modifier.height(6.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = iosCardBg,
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 1.dp,
            shadowElevation = 0.dp,
        ) {
            SelectionContainer {
                Text(
                    walletAddress,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
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

        // Sync Status (iOS-like, theme-aware)
        Text(
            "Sync Status",
            color = iosSecondary,
        )
        Spacer(Modifier.height(8.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = iosCardBg,
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 1.dp,
            shadowElevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                KeyValueRow("Chain Height", chainHeight.toString(), labelColor = iosSecondary, valueColor = iosPrimaryText)
                KeyValueRow("Last Scanned", lastScanned.toString(), labelColor = iosSecondary, valueColor = iosPrimaryText)
                if (!isSynced) {
                    KeyValueRow("Remaining Blocks", remainingBlocks.toString(), labelColor = iosSecondary, valueColor = iosPrimaryText)
                }
                KeyValueRow("Target Height", targetHeight.toString(), labelColor = iosSecondary, valueColor = iosPrimaryText)
                KeyValueRow("Restore Height", restoreHeight.toString(), labelColor = iosSecondary, valueColor = iosPrimaryText)

                // iOS parity rows (MoneroConfig.accountGap / MoneroConfig.gapLimit)
                if (accountGap != null) {
                    KeyValueRow("Accounts (lookahead)", accountGap.toString(), labelColor = iosSecondary, valueColor = iosPrimaryText)
                }
                if (gapLimit != null) {
                    KeyValueRow("Gap limit", gapLimit.toString(), labelColor = iosSecondary, valueColor = iosPrimaryText)
                }

                KeyValueRow("Throughput", String.format("%.1f blk/s", blocksPerSecSmoothed), labelColor = iosSecondary, valueColor = iosPrimaryText)

                Spacer(Modifier.height(10.dp))

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = iosBlue,
                    trackColor = iosSeparator,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (isSynced) "Wallet is fully synced"
            else if (targetHeight == 0L) "Initializing scan…"
            else if (lastScanned == restoreHeight) "Initializing scan…"
            else "Syncing… $remainingBlocks blocks remaining @ ${String.format("%.1f", blocksPerSecSmoothed)} blks/s",
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
private fun KeyValueRow(
    label: String,
    value: String,
    labelColor: Color = Color.Gray,
    valueColor: Color = Color.Unspecified,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = labelColor
        )
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            color = valueColor
        )
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
    val context = LocalContext.current

    var nodeUrlInput by remember {
        mutableStateOf(state.nodeUrl ?: walletManager.defaultNodeUrl())
    }

    // Persisted scan tuning (iOS parity)
    var gapLimitInput by remember {
        mutableStateOf(MoneroConfig.gapLimit(context).toString())
    }
    var accountGapInput by remember {
        mutableStateOf(MoneroConfig.accountGap(context).toString())
    }

    // Validation state (keep messages close to the inputs).
    var gapLimitError by remember { mutableStateOf<String?>(null) }
    var accountGapError by remember { mutableStateOf<String?>(null) }

    var statusText by remember { mutableStateOf<String?>(null) }

    // Debug snapshot (best-effort, for parity troubleshooting).
    val sync = state.syncStatus
    val cacheInfo = state.cacheInfo
    val coreErr = runCatching { WalletCore.lastErrorMessage() }.getOrNull()
    val derivedAddr = state.walletAddress
    val tipKnown = sync != null && sync.chainHeight > sync.restoreHeight

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
            Text("Save node URL")
        }

        Spacer(Modifier.height(8.dp))

        Text("Current node: ${state.nodeUrl ?: walletManager.defaultNodeUrl()}")

        Spacer(Modifier.height(16.dp))

        Text("Scan tuning (iOS parity)", color = Color.Gray)
        Spacer(Modifier.height(8.dp))

        Text("Gap limit (subaddresses per account)")
        OutlinedTextField(
            value = gapLimitInput,
            onValueChange = {
                gapLimitInput = it
                gapLimitError = null
                statusText = null
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = gapLimitError != null,
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
            placeholder = { Text(MoneroConfig.DEFAULT_GAP_LIMIT.toString()) }
        )
        Text(
            gapLimitError ?: "Valid range: 1..100000 (default ${MoneroConfig.DEFAULT_GAP_LIMIT})",
            color = if (gapLimitError != null) Color(0xFFFF3B30) else Color.Gray
        )

        Spacer(Modifier.height(8.dp))

        Text("Accounts (lookahead)")
        OutlinedTextField(
            value = accountGapInput,
            onValueChange = {
                accountGapInput = it
                accountGapError = null
                statusText = null
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = accountGapError != null,
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
            placeholder = { Text(MoneroConfig.DEFAULT_ACCOUNT_GAP.toString()) }
        )
        Text(
            accountGapError ?: "Valid range: 1..1000 (default ${MoneroConfig.DEFAULT_ACCOUNT_GAP})",
            color = if (accountGapError != null) Color(0xFFFF3B30) else Color.Gray
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                statusText = null
                gapLimitError = null
                accountGapError = null

                scope.launch {
                    try {
                        val glRaw = gapLimitInput.trim().toIntOrNull()
                        val agRaw = accountGapInput.trim().toIntOrNull()

                        if (glRaw == null) {
                            gapLimitError = "Enter a whole number"
                            return@launch
                        }
                        if (agRaw == null) {
                            accountGapError = "Enter a whole number"
                            return@launch
                        }

                        // Clamp exactly like MoneroConfig does (iOS parity).
                        val glClamped = glRaw.coerceIn(1, 100_000)
                        val agClamped = agRaw.coerceIn(1, 1_000)

                        // If we had to clamp, update the text fields so the UI reflects what we saved.
                        if (glClamped != glRaw) gapLimitInput = glClamped.toString()
                        if (agClamped != agRaw) accountGapInput = agClamped.toString()

                        MoneroConfig.setGapLimit(context, glClamped)
                        MoneroConfig.setAccountGap(context, agClamped)

                        // Best-effort: apply immediately if a wallet is open.
                        // Refresh will also re-apply at start.
                        val wid = state.walletId
                        if (!wid.isNullOrBlank()) {
                            runCatching { WalletCore.setGapLimit(wid, MoneroConfig.gapLimit(context)) }
                            runCatching { WalletCore.setAccountGap(MoneroConfig.accountGap(context)) }
                        }

                        statusText = "Saved scan tuning"
                    } catch (t: Throwable) {
                        statusText = "Failed to save scan tuning: ${t.message ?: t.javaClass.simpleName}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save scan tuning")
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "Effective gapLimit=${MoneroConfig.gapLimit(context)} accountGap=${MoneroConfig.accountGap(context)}",
            color = Color.Gray
        )

        statusText?.let {
            Spacer(Modifier.height(8.dp))
            Text(it)
        }

        Spacer(Modifier.height(16.dp))

        Text("Debug", color = Color.Gray)
        Spacer(Modifier.height(8.dp))

        // Derived primary address (parity troubleshooting).
        Text("Derived address", color = Color.Gray)
        SelectionContainer {
            Text(
                derivedAddr ?: "(none)",
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                statusText = null
                walletManager.recomputeDerivedAddressFromStoredMetadata()
                statusText = "Requested address recompute"
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Recompute derived address")
        }

        Spacer(Modifier.height(8.dp))

        // Sync status snapshot
        Text("Sync status", color = Color.Gray)
        Text(
            "chainHeight=${sync?.chainHeight ?: 0} lastScanned=${sync?.lastScanned ?: 0} restoreHeight=${sync?.restoreHeight ?: 0} tipKnown=$tipKnown",
            fontFamily = FontFamily.Monospace
        )

        Spacer(Modifier.height(8.dp))

        // Cache info snapshot
        Text("Cache", color = Color.Gray)
        Text(
            "path=${cacheInfo?.filePath ?: "(none)"} bytes=${cacheInfo?.bytesOnDisk ?: 0} savedAtMs=${cacheInfo?.lastSavedAtMs ?: 0}",
            fontFamily = FontFamily.Monospace
        )

        Spacer(Modifier.height(8.dp))

        // Core error state
        Text("Core last error", color = Color.Gray)
        SelectionContainer {
            Text(
                coreErr ?: "<null>",
                fontFamily = FontFamily.Monospace
            )
        }
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
