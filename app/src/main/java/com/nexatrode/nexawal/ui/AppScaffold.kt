package com.nexatrode.nexawal.ui

import android.content.Intent
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.Switch
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
import com.nexatrode.nexawal.DeviceAuthGate
import com.nexatrode.nexawal.MoneroConfig
import com.nexatrode.nexawal.MoneroQr
import com.nexatrode.nexawal.WalletManager.ReceiveSubaddressEntry
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
        BottomNavItem.Receive,
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
            composable(BottomNavItem.Receive.route) {
                ReceiveScreen(walletManager = walletManager)
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

    data object Receive : BottomNavItem(
        route = "receive",
        label = "Receive",
        icon = Icons.Filled.Home
    )

    data object Settings : BottomNavItem(
        route = "settings",
        label = "Settings",
        icon = Icons.Filled.Settings
    )
}

/**
 * Wallet screen:
 * - Balance card (Total + Unlocked)
 * - Address card (monospace, selectable)
 * - Sync summary + progress
 * - Transactions list (tap row -> details dialog)
 * - Refresh/cancel actions
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
                val syncHeadline = when {
                    isSynced -> "Wallet is fully synced"
                    targetHeight == 0L -> "Connecting to node"
                    state.refreshInProgress && lastScanned == restoreHeight -> "Fetching first block batch"
                    state.refreshInProgress && blocksPerSecSmoothed <= 0.0 -> "Fetching more blocks"
                    else -> "Syncing wallet"
                }
                val syncDetail = when {
                    isSynced -> "Scanned to block $lastScanned"
                    targetHeight == 0L -> "Waiting for remote chain height"
                    state.refreshInProgress && lastScanned == restoreHeight -> "Node is responding. Waiting for initial blocks from height $restoreHeight"
                    state.refreshInProgress && blocksPerSecSmoothed <= 0.0 -> "Slow remote node response. $remainingBlocks blocks remaining"
                    else -> "$remainingBlocks blocks remaining"
                }

                Text(syncHeadline, color = iosPrimaryText, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(syncDetail, color = iosSecondary)
                Spacer(Modifier.height(10.dp))

                KeyValueRow("Scanned Height", lastScanned.toString(), labelColor = iosSecondary, valueColor = iosPrimaryText)
                if (targetHeight > 0L) {
                    KeyValueRow("Network Height", targetHeight.toString(), labelColor = iosSecondary, valueColor = iosPrimaryText)
                }
                if (!isSynced) {
                    KeyValueRow("Remaining Blocks", remainingBlocks.toString(), labelColor = iosSecondary, valueColor = iosPrimaryText)
                }
                KeyValueRow("Restore Height", restoreHeight.toString(), labelColor = iosSecondary, valueColor = iosPrimaryText)
                if (state.refreshInProgress && blocksPerSecSmoothed > 0.0) {
                    KeyValueRow("Scan Speed", String.format("%.1f blk/s", blocksPerSecSmoothed), labelColor = iosSecondary, valueColor = iosPrimaryText)
                }

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
            else if (targetHeight == 0L) "Connecting to node…"
            else if (state.refreshInProgress && lastScanned == restoreHeight) "Fetching first block batch from node…"
            else if (state.refreshInProgress && blocksPerSecSmoothed <= 0.0) "Fetching next block batch from node…"
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

        // Actions row
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
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Refresh Wallet")
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "Use the Send and Receive tabs for payments and new subaddresses.",
                color = iosSecondary
            )
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
 * Receive screen with persisted receive subaddresses, QR, copy, and share.
 */
@Composable
private fun ReceiveScreen(walletManager: WalletManager) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = ClipboardCompat.current()

    var receiveEntries by remember { mutableStateOf<List<ReceiveSubaddressEntry>>(emptyList()) }
    var selectedSubaddressIndex by remember { mutableStateOf(0) }
    var receiveAddress by remember { mutableStateOf("") }
    var amountXmr by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf<String?>(null) }
    var showCreatePrompt by remember { mutableStateOf(false) }
    var newLabel by remember { mutableStateOf("") }

    suspend fun refreshAddressBook() {
        val book = walletManager.loadReceiveSubaddressBook()
        receiveEntries = book.entries
        if (book.entries.none { it.subaddressIndex == selectedSubaddressIndex }) {
            selectedSubaddressIndex = 0
        }
        receiveAddress = walletManager.deriveReceiveAddress(selectedSubaddressIndex)
    }

    LaunchedEffect(Unit) {
        runCatching { refreshAddressBook() }
            .onFailure { statusText = it.message ?: it.javaClass.simpleName }
    }

    LaunchedEffect(selectedSubaddressIndex) {
        if (receiveEntries.isEmpty()) return@LaunchedEffect
        runCatching {
            receiveAddress = walletManager.deriveReceiveAddress(selectedSubaddressIndex)
        }.onFailure { statusText = it.message ?: it.javaClass.simpleName }
    }

    val paymentUri = if (receiveAddress.isNotBlank()) {
        MoneroQr.buildUri(
            address = receiveAddress,
            amountXmr = amountXmr.trim().takeIf { it.isNotEmpty() },
            description = description.trim().takeIf { it.isNotEmpty() },
        )
    } else {
        ""
    }

    val qrBitmap = remember(paymentUri) {
        runCatching {
            if (paymentUri.isNotEmpty()) MoneroQr.qrBitmap(paymentUri, sizePx = 640) else null
        }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Receive")
        Spacer(Modifier.height(12.dp))

        Text("Address", color = Color.Gray)
        Spacer(Modifier.height(6.dp))

        if (receiveEntries.isEmpty()) {
            Text("Loading receive addresses…", color = Color.Gray)
        } else {
            Text(
                receiveEntries.firstOrNull { it.subaddressIndex == selectedSubaddressIndex }?.let {
                    val label = it.label.trim()
                    if (label.isEmpty()) "Selected: Subaddress ${it.subaddressIndex}" else "Selected: $label"
                } ?: "Selected: Subaddress $selectedSubaddressIndex"
            )

            Spacer(Modifier.height(8.dp))

            receiveEntries.forEach { entry ->
                val title = entry.label.trim().ifEmpty { "Subaddress ${entry.subaddressIndex}" }
                Button(
                    onClick = { selectedSubaddressIndex = entry.subaddressIndex },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (entry.subaddressIndex == selectedSubaddressIndex) "Selected: $title" else title)
                }
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { showCreatePrompt = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("New receive address")
            }
        }

        Spacer(Modifier.height(12.dp))

        Text("Monero address", color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Text(receiveAddress.ifBlank { "(unavailable)" }, fontFamily = FontFamily.Monospace)
        }

        Spacer(Modifier.height(12.dp))

        Text("Amount (optional)", color = Color.Gray)
        OutlinedTextField(
            value = amountXmr,
            onValueChange = { amountXmr = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("0.0") }
        )

        Spacer(Modifier.height(8.dp))

        Text("Description (optional)", color = Color.Gray)
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("What is this for?") }
        )

        Spacer(Modifier.height(12.dp))

        Text("Monero URI", color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Text(
                paymentUri.ifBlank { "(unavailable)" },
                fontFamily = FontFamily.Monospace,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(12.dp))

        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "Monero receive QR"
            )
        } else {
            Text("QR unavailable", color = Color.Gray)
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                scope.launch {
                    ClipboardCompat.setText(clipboard, if (paymentUri.isNotBlank()) paymentUri else receiveAddress)
                    statusText = "Copied"
                }
            },
            enabled = receiveAddress.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Copy")
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                if (paymentUri.isBlank() || qrBitmap == null) {
                    statusText = "Nothing to share yet"
                    return@Button
                }

                runCatching {
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
                        putExtra(Intent.EXTRA_TEXT, paymentUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share"))
                }.onFailure {
                    statusText = "Share failed: ${it.message ?: it.javaClass.simpleName}"
                }
            },
            enabled = paymentUri.isNotBlank() && qrBitmap != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Share")
        }

        statusText?.let {
            Spacer(Modifier.height(12.dp))
            Text(it)
        }
    }

    if (showCreatePrompt) {
        AlertDialog(
            onDismissRequest = { showCreatePrompt = false },
            title = { Text("New address label") },
            text = {
                OutlinedTextField(
                    value = newLabel,
                    onValueChange = { newLabel = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Optional label") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    val label = newLabel.trim()
                    newLabel = ""
                    showCreatePrompt = false
                    scope.launch {
                        runCatching {
                            val created = walletManager.createReceiveSubaddress(label)
                            refreshAddressBook()
                            selectedSubaddressIndex = created.subaddressIndex
                        }.onFailure {
                            statusText = it.message ?: it.javaClass.simpleName
                        }
                    }
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                Button(onClick = {
                    newLabel = ""
                    showCreatePrompt = false
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Send screen: fee preview, send, and send max (sweep) using WalletManager.
 */
@Composable
private fun SendScreen(walletManager: WalletManager) {
    val scope = rememberCoroutineScope()
    val state by walletManager.state.collectAsState()
    val context = LocalContext.current

    var toAddress by remember { mutableStateOf("") }
    var amountXmrText by remember { mutableStateOf("") }
    var isEstimating by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var isPreviewingMax by remember { mutableStateOf(false) }
    var estimatedFee by remember { mutableStateOf<SendJson.FeeResult?>(null) }
    var sweepPreview by remember { mutableStateOf<SendJson.SweepPreviewResult?>(null) }
    var sendResult by remember { mutableStateOf<SendJson.SendResult?>(null) }
    var sweepResult by remember { mutableStateOf<SendJson.SweepSendResult?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var infoText by remember { mutableStateOf<String?>(null) }

    val unlockedXmr = state.balance?.unlockedXmr ?: "0.000000000000"
    val hasWallet = !state.walletId.isNullOrBlank()

    fun canPreviewFee(): Boolean = hasWallet && toAddress.trim().isNotEmpty() && amountXmrText.trim().isNotEmpty() && !isEstimating && !isSending
    fun canSendExact(): Boolean = canPreviewFee() && estimatedFee != null
    fun canSendMax(): Boolean = hasWallet && toAddress.trim().isNotEmpty() && !isEstimating && !isSending
    fun totalWithFeeText(): String? {
        val fee = estimatedFee ?: return null
        val amount = runCatching { parseXmrToPiconero(amountXmrText) }.getOrNull() ?: return null
        return XmrFormat.formatPiconeroAsXmr(amount + fee.fee)
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Send")
        Spacer(Modifier.height(8.dp))

        Text("Unlocked balance: $unlockedXmr XMR", color = Color.Gray)
        Spacer(Modifier.height(12.dp))

        Text("To address")
        OutlinedTextField(
            value = toAddress,
            onValueChange = {
                toAddress = it
                estimatedFee = null
                sweepPreview = null
                sendResult = null
                sweepResult = null
                errorText = null
                infoText = null
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        Text("Amount (XMR)")
        OutlinedTextField(
            value = amountXmrText,
            onValueChange = {
                amountXmrText = it
                estimatedFee = null
                sendResult = null
                sweepResult = null
                errorText = null
                infoText = null
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        val mergedError = errorText ?: state.lastError
        if (mergedError != null) {
            Text(mergedError, color = Color(0xFFFF3B30))
            Spacer(Modifier.height(12.dp))
        }

        infoText?.let {
            Text(it)
            Spacer(Modifier.height(12.dp))
        }

        estimatedFee?.let { fee ->
            Text("Estimated fee: ${fee.feeXmr} XMR")
            totalWithFeeText()?.let { total ->
                Text("Total (amount + fee): $total XMR", color = Color.Gray)
            }
            Spacer(Modifier.height(12.dp))
        }

        sweepPreview?.let { preview ->
            Text("Send max amount: ${preview.amountXmr} XMR")
            Text("Estimated fee: ${preview.feeXmr} XMR", color = Color.Gray)
            Spacer(Modifier.height(12.dp))
        }

        sendResult?.let { result ->
            Text("Sent", color = Color.Gray)
            Text("TXID: ${result.txid}", fontFamily = FontFamily.Monospace)
            Text("Fee: ${result.feeXmr} XMR")
            Spacer(Modifier.height(12.dp))
        }

        sweepResult?.let { result ->
            Text("Sent max", color = Color.Gray)
            Text("TXID: ${result.txid}", fontFamily = FontFamily.Monospace)
            Text("Amount: ${result.amountXmr} XMR")
            Text("Fee: ${result.feeXmr} XMR")
            Spacer(Modifier.height(12.dp))
        }

        Button(
            onClick = {
                errorText = null
                infoText = null
                sendResult = null
                sweepResult = null
                sweepPreview = null
                scope.launch {
                    isEstimating = true
                    try {
                        val amountPiconero = parseXmrToPiconero(amountXmrText)
                        estimatedFee = walletManager.previewFee(
                            destinations = listOf(
                                SendJson.Destination(
                                    address = toAddress.trim(),
                                    amount = amountPiconero
                                )
                            )
                        )
                        infoText = "Fee estimated successfully."
                    } catch (t: Throwable) {
                        errorText = t.message ?: t.javaClass.simpleName
                    } finally {
                        isEstimating = false
                    }
                }
            },
            enabled = canPreviewFee(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isEstimating) "Estimating..." else "Preview fee")
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                errorText = null
                infoText = null
                sendResult = null
                sweepResult = null
                scope.launch {
                    isSending = true
                    try {
                        val amountPiconero = parseXmrToPiconero(amountXmrText)

                        if (MoneroConfig.requireDeviceAuth(context)) {
                            val activity = context as? ComponentActivity
                                ?: throw IllegalStateException("Device authentication requires an activity context")
                            DeviceAuthGate.authenticate(
                                activity = activity,
                                title = "Confirm send",
                                subtitle = "Authenticate to send Monero"
                            )
                        }

                        sendResult = walletManager.send(
                            toAddress = toAddress.trim(),
                            amountPiconero = amountPiconero
                        )
                        infoText = "Transaction broadcast."
                        walletManager.refreshWalletDataSnapshots()
                    } catch (t: Throwable) {
                        errorText = t.message ?: t.javaClass.simpleName
                    } finally {
                        isSending = false
                    }
                }
            },
            enabled = canSendExact(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isSending) "Sending..." else "Send")
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                errorText = null
                infoText = null
                sendResult = null
                sweepResult = null
                estimatedFee = null
                scope.launch {
                    isPreviewingMax = true
                    try {
                        sweepPreview = walletManager.previewSweep(toAddress = toAddress.trim())
                        infoText = "Maximum sendable amount estimated."
                    } catch (t: Throwable) {
                        errorText = t.message ?: t.javaClass.simpleName
                    } finally {
                        isPreviewingMax = false
                    }
                }
            },
            enabled = canSendMax(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isPreviewingMax) "Estimating max..." else "Preview send max")
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                errorText = null
                infoText = null
                sendResult = null
                scope.launch {
                    isSending = true
                    try {
                        if (MoneroConfig.requireDeviceAuth(context)) {
                            val activity = context as? ComponentActivity
                                ?: throw IllegalStateException("Device authentication requires an activity context")
                            DeviceAuthGate.authenticate(
                                activity = activity,
                                title = "Confirm send max",
                                subtitle = "Authenticate to sweep the wallet balance"
                            )
                        }

                        sweepResult = walletManager.sweep(toAddress = toAddress.trim())
                        infoText = "Maximum spendable balance broadcast."
                        walletManager.refreshWalletDataSnapshots()
                    } catch (t: Throwable) {
                        errorText = t.message ?: t.javaClass.simpleName
                    } finally {
                        isSending = false
                    }
                }
            },
            enabled = canSendMax(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isSending) "Sending..." else "Send max")
        }
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
    var requireDeviceAuth by remember {
        mutableStateOf(MoneroConfig.requireDeviceAuth(context))
    }
    var showAdvancedRecovery by remember { mutableStateOf(false) }

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

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Require device auth for wallet unlock and send",
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = requireDeviceAuth,
                onCheckedChange = {
                    requireDeviceAuth = it
                    MoneroConfig.setRequireDeviceAuth(context, it)
                    statusText = if (it) {
                        "Enabled device auth"
                    } else {
                        "Disabled device auth"
                    }
                }
            )
        }

        Text(
            if (DeviceAuthGate.isAvailable(context)) {
                "Biometric or device credential authentication is available on this device."
            } else {
                "Biometric or device credential authentication is not currently available on this device."
            },
            color = Color.Gray
        )

        Spacer(Modifier.height(16.dp))

        Text("Restore & Rescan", color = Color.Gray)
        Spacer(Modifier.height(8.dp))

        Text("Use an earlier height if funds are missing after import, or rescan from 0 if you need a full recovery.")
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

                        val glClamped = glRaw.coerceIn(1, 100_000)
                        val agClamped = agRaw.coerceIn(1, 1_000)

                        if (glClamped != glRaw) gapLimitInput = glClamped.toString()
                        if (agClamped != agRaw) accountGapInput = agClamped.toString()

                        MoneroConfig.setGapLimit(context, glClamped)
                        MoneroConfig.setAccountGap(context, agClamped)

                        val wid = state.walletId
                        if (!wid.isNullOrBlank()) {
                            runCatching { WalletCore.setGapLimit(wid, MoneroConfig.gapLimit(context)) }
                            runCatching { WalletCore.setAccountGap(MoneroConfig.accountGap(context)) }
                        }

                        statusText = "Saved recovery scan settings"
                    } catch (t: Throwable) {
                        statusText = "Failed to save recovery scan settings: ${t.message ?: t.javaClass.simpleName}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Apply restore/recovery settings")
        }

        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Advanced recovery",
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { showAdvancedRecovery = !showAdvancedRecovery }) {
                Text(if (showAdvancedRecovery) "Hide" else "Show")
            }
        }

        if (showAdvancedRecovery) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Only change these values if a wallet import appears incomplete after using the correct restore height.",
                color = Color.Gray
            )

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

            Text(
                "Effective gapLimit=${MoneroConfig.gapLimit(context)} accountGap=${MoneroConfig.accountGap(context)}",
                color = Color.Gray
            )
        }

        statusText?.let {
            Spacer(Modifier.height(8.dp))
            Text(it)
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
