package com.nexatrode.nexawal.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.nexatrode.nexawal.R
import com.nexatrode.nexawal.WalletManager.ReceiveSubaddressEntry
import com.nexatrode.nexawal.SendJson
import com.nexatrode.nexawal.TimeFormat
import com.nexatrode.nexawal.Transfer
import com.nexatrode.nexawal.WalletManager
import com.nexatrode.nexawal.XmrFormat
import com.nexatrode.nexawal.walletcore.WalletCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat

internal data class NexaPalette(
    val background: Color,
    val card: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val separator: Color,
    val accent: Color,
    val secondaryAction: Color,
    val success: Color,
    val danger: Color,
    val border: Color,
    val cta: Color,
    val ctaText: Color,
    val classic: Boolean, // true == neon terminal theme
)

@Composable
internal fun rememberNexaPalette(classicUI: Boolean): NexaPalette {
    val dark = isSystemInDarkTheme()
    // Classic UI setting ON = non-neon standard look.
    // Setting OFF (default) = neon terminal theme (`palette.classic == true`).
    val neon = !classicUI
    return remember(dark, neon) {
        if (neon) {
            if (dark) {
                NexaPalette(
                    background = Color(0xFF000000),
                    card = Color(0xFF0A0F0A),
                    primaryText = Color(0xFF39FF14),
                    secondaryText = Color(0xFF59BF66),
                    separator = Color(0xFF1A3D1A),
                    accent = Color(0xFF39FF14),
                    secondaryAction = Color(0xFF0A0F0A),
                    success = Color(0xFF39FF14),
                    danger = Color(0xFFFF5959),
                    border = Color(0xFF00E676),
                    cta = Color(0xFF39FF14),
                    ctaText = Color(0xFF001A12),
                    classic = true,
                )
            } else {
                NexaPalette(
                    background = Color(0xFFF2F4F2),
                    card = Color(0xFFFFFFFF),
                    primaryText = Color(0xFF0D2E14),
                    secondaryText = Color(0xFF406648),
                    separator = Color(0xFFC8DCC8),
                    accent = Color(0xFF0A7A2F),
                    secondaryAction = Color(0xFFFFFFFF),
                    success = Color(0xFF0A7A2F),
                    danger = Color(0xFFB31E1E),
                    border = Color(0xFF0A7A2F),
                    cta = Color(0xFF0A7A2F),
                    ctaText = Color(0xFFFFFFFF),
                    classic = true,
                )
            }
        } else {
            NexaPalette(
                background = if (dark) Color(0xFF0B0F14) else Color(0xFFF2F2F7),
                card = if (dark) Color(0xFF171C22) else Color(0xFFFFFFFF),
                primaryText = if (dark) Color(0xFFF5F7FA) else Color(0xFF111111),
                secondaryText = if (dark) Color(0xFF8E98AA) else Color(0xFF6D6D72),
                separator = if (dark) Color(0xFF262D36) else Color(0xFFE5E5EA),
                accent = Color(0xFFFF6B35),
                secondaryAction = if (dark) Color(0xFF242B35) else Color(0xFFF5F6FA),
                success = Color(0xFF34C759),
                danger = Color(0xFFFF3B30),
                border = if (dark) Color(0xFF262D36) else Color(0xFFE5E5EA),
                cta = Color(0xFFFF6B35),
                ctaText = Color(0xFFFFFFFF),
                classic = false,
            )
        }
    }
}

@Composable
private fun ScreenHeading(
    title: String,
    subtitle: String,
    palette: NexaPalette,
) {
    Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = palette.primaryText)
    Spacer(Modifier.height(6.dp))
    Text(subtitle, color = palette.secondaryText, fontSize = 15.sp, lineHeight = 21.sp)
}

@Composable
private fun SectionLabel(text: String, palette: NexaPalette) {
    Text(text, color = palette.secondaryText, fontWeight = FontWeight.Medium, fontSize = 13.sp)
}

@Composable
private fun SectionCard(
    palette: NexaPalette,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(if (palette.classic) 4.dp else 16.dp)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = palette.card,
        shape = shape,
        tonalElevation = if (palette.classic) 0.dp else 1.dp,
        shadowElevation = 0.dp,
        border = if (palette.classic) BorderStroke(1.dp, palette.border) else null,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

private fun formatGrouped(value: Long): String = NumberFormat.getIntegerInstance().format(value)

@Composable
internal fun rememberAppPalette(classicUI: Boolean = MoneroConfig.isClassicUIEnabled(LocalContext.current)): NexaPalette {
    return rememberNexaPalette(classicUI)
}

@Composable
internal fun nexaFieldColors(palette: NexaPalette) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = palette.primaryText,
    unfocusedTextColor = palette.primaryText,
    disabledTextColor = palette.secondaryText,
    focusedBorderColor = palette.border,
    unfocusedBorderColor = palette.separator,
    disabledBorderColor = palette.separator,
    cursorColor = palette.accent,
    focusedLabelColor = palette.secondaryText,
    unfocusedLabelColor = palette.secondaryText,
    focusedPlaceholderColor = palette.secondaryText,
    unfocusedPlaceholderColor = palette.secondaryText,
    focusedContainerColor = palette.card,
    unfocusedContainerColor = palette.card,
)

@Composable
internal fun nexaSwitchColors(palette: NexaPalette) = SwitchDefaults.colors(
    checkedThumbColor = palette.ctaText,
    checkedTrackColor = palette.accent,
    checkedBorderColor = palette.border,
    uncheckedThumbColor = palette.secondaryText,
    uncheckedTrackColor = palette.separator,
    uncheckedBorderColor = palette.separator,
)

@Composable
internal fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    palette: NexaPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val neon = palette.classic
    val container = if (neon) palette.cta else Color(0xFFFF6B35)
    val content = if (neon) palette.ctaText else Color.White
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(if (neon) 28.dp else 14.dp),
        border = if (neon) BorderStroke(1.dp, palette.border.copy(alpha = 0.35f)) else null,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = if (neon) Color(0xFF1A1F1A) else Color(0xFFFF6B35).copy(alpha = 0.4f),
            disabledContentColor = if (neon) palette.secondaryText.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.7f),
        )
    ) {
        Text(
            text,
            color = if (enabled) content else (if (neon) palette.secondaryText.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.7f)),
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (neon) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
internal fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    palette: NexaPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val neon = palette.classic
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(if (neon) 28.dp else 14.dp),
        border = BorderStroke(if (neon) 1.dp else 1.dp, if (neon) palette.border else palette.separator),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (neon) Color(0xFF121612) else palette.secondaryAction,
            contentColor = if (neon) palette.accent else palette.primaryText,
            disabledContainerColor = if (neon) Color(0xFF121612) else palette.secondaryAction.copy(alpha = 0.5f),
            disabledContentColor = if (neon) palette.secondaryText.copy(alpha = 0.45f) else palette.primaryText.copy(alpha = 0.5f),
        )
    ) {
        Text(
            text,
            color = if (enabled) {
                if (neon) palette.accent else palette.primaryText
            } else {
                if (neon) palette.secondaryText.copy(alpha = 0.45f) else palette.primaryText.copy(alpha = 0.5f)
            },
            fontWeight = FontWeight.Medium,
            fontFamily = if (neon) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

/**
 * Root application scaffold with bottom-tab navigation.
 */
@Composable
fun AppScaffold(
    walletManager: WalletManager,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    var classicUI by remember { mutableStateOf(MoneroConfig.isClassicUIEnabled(context)) }
    val palette = rememberNexaPalette(classicUI)
    val items = listOf(
        BottomNavItem.Wallet,
        BottomNavItem.Receive,
        BottomNavItem.Send,
        BottomNavItem.Settings,
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val neonScheme = if (palette.classic) {
        if (isSystemInDarkTheme()) {
            darkColorScheme(
                primary = palette.accent,
                onPrimary = palette.ctaText,
                secondary = palette.border,
                onSecondary = palette.ctaText,
                tertiary = palette.accent,
                background = palette.background,
                onBackground = palette.primaryText,
                surface = palette.card,
                onSurface = palette.primaryText,
                surfaceVariant = palette.card,
                onSurfaceVariant = palette.secondaryText,
                outline = palette.border,
            )
        } else {
            lightColorScheme(
                primary = palette.accent,
                onPrimary = palette.ctaText,
                secondary = palette.border,
                onSecondary = Color.White,
                tertiary = palette.accent,
                background = palette.background,
                onBackground = palette.primaryText,
                surface = palette.card,
                onSurface = palette.primaryText,
                surfaceVariant = palette.card,
                onSurfaceVariant = palette.secondaryText,
                outline = palette.border,
            )
        }
    } else {
        null
    }

    val scaffoldContent = @Composable {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = palette.background,
        bottomBar = {
            NavigationBar(
                containerColor = palette.card,
                contentColor = palette.secondaryText
            ) {
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
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = palette.accent,
                            selectedTextColor = palette.primaryText,
                            indicatorColor = palette.separator,
                            unselectedIconColor = palette.secondaryText,
                            unselectedTextColor = palette.secondaryText,
                        ),
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = if (selected) palette.accent else palette.secondaryText
                            )
                        },
                        label = {
                            Text(
                                if (palette.classic) item.label.uppercase() else item.label,
                                color = if (selected) palette.primaryText else palette.secondaryText,
                                fontFamily = if (palette.classic) FontFamily.Monospace else FontFamily.Default,
                                fontSize = if (palette.classic) 11.sp else 12.sp,
                            )
                        }
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
                WalletScreen(
                    walletManager = walletManager,
                    palette = palette,
                    onOpenSend = { navController.navigate(BottomNavItem.Send.route) },
                    onOpenReceive = { navController.navigate(BottomNavItem.Receive.route) },
                )
            }
            composable(BottomNavItem.Send.route) {
                SendScreen(walletManager = walletManager, palette = palette)
            }
            composable(BottomNavItem.Receive.route) {
                ReceiveScreen(walletManager = walletManager, palette = palette)
            }
            composable(BottomNavItem.Settings.route) {
                SettingsScreen(
                    walletManager = walletManager,
                    classicUI = classicUI,
                    onClassicUIChange = { enabled ->
                        classicUI = enabled
                        MoneroConfig.setClassicUIEnabled(context, enabled)
                    },
                )
            }
        }
    }
    }

    if (neonScheme != null) {
        MaterialTheme(colorScheme = neonScheme, content = scaffoldContent)
    } else {
        scaffoldContent()
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
        icon = Icons.Filled.QrCode
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
private fun WalletScreen(
    walletManager: WalletManager,
    palette: NexaPalette,
    onOpenSend: () -> Unit,
    onOpenReceive: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by walletManager.state.collectAsState()
    val scroll = rememberScrollState()
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
        val balanceIntervalMs = 60_000L
        val transfersIntervalMs = 120_000L

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

    val totalPiconero = state.balance?.totalPiconero ?: 0L
    val unlockedPiconero = state.balance?.unlockedPiconero ?: 0L
    val totalXmr = XmrFormat.formatPiconeroAsDisplayXmr(totalPiconero)
    val unlockedXmr = XmrFormat.formatPiconeroAsDisplayXmr(unlockedPiconero)
    val showUnlockedBalance = unlockedPiconero > 0L && unlockedPiconero != totalPiconero

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
    val syncTolerance = 3L
    val isSynced = targetHeight > 0L && lastScanned + syncTolerance >= targetHeight

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
    val iosBlue = if (palette.classic) palette.accent else Color(0xFF007AFF)
    val iosGroupedBg = palette.background
    val iosCardBg = palette.card
    val iosSecondary = palette.secondaryText
    val iosSeparator = palette.separator
    val iosPrimaryText = palette.primaryText
    val chromeFont = if (palette.classic) FontFamily.Monospace else FontFamily.Default

    // Match iOS WalletView sizing:
    // - Total: 36pt bold
    // - Unlocked: 24pt semibold
    val totalAmountSp = 36.sp
    val suffixSp = 14.sp

    // Match iOS progress semantics:
    // - use the stable target captured at refresh start
    // - measure completed work from restoreHeight to targetHeight
    // - clamp near-tip within tolerance to 100%
    val progress = when {
        targetHeight <= 0L -> 0f
        isSynced -> 1f
        targetHeight <= restoreHeight -> 0f
        else -> {
            val clampedScanned = minOf(lastScanned, targetHeight)
            val workSpan = (targetHeight - restoreHeight).coerceAtLeast(1L)
            val completed = (clampedScanned - restoreHeight).coerceAtLeast(0L)
            (completed.toDouble() / workSpan.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }
    }
    val progressPercentText = String.format("%.1f%%", progress * 100f)

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
        SectionCard(palette = palette) {
            Box(modifier = Modifier.fillMaxWidth()) {
                if (palette.classic) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground_art),
                        contentDescription = null,
                        modifier = Modifier
                            .size(140.dp)
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp)
                            .alpha(0.12f),
                    )
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (palette.classic) "NEXAWAL" else "Total Balance",
                        color = if (palette.classic) iosPrimaryText else iosSecondary,
                        fontFamily = chromeFont,
                        fontWeight = if (palette.classic) FontWeight.Bold else FontWeight.Normal,
                        letterSpacing = if (palette.classic) 2.sp else 0.sp,
                    )
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

                    if (showUnlockedBalance) {
                        Spacer(Modifier.height(14.dp))

                        Text(
                            if (palette.classic) "UNLOCKED" else "Unlocked",
                            color = iosSecondary,
                            fontFamily = chromeFont,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 20.sp,
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
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            PrimaryActionButton(
                text = if (palette.classic) "SEND" else "Send",
                onClick = onOpenSend,
                palette = palette,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            SecondaryActionButton(
                text = if (palette.classic) "RECEIVE" else "Receive",
                onClick = onOpenReceive,
                palette = palette,
                modifier = Modifier.weight(1f)
            )
        }

        if (state.balanceIsStaleWhileSyncing) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Balance updating while sync catches up",
                color = iosSecondary
            )
        }

        Spacer(Modifier.height(16.dp))

        // Sync Status (iOS-like, theme-aware)
        SectionLabel(if (palette.classic) "STATUS" else "Status", palette)
        Spacer(Modifier.height(8.dp))

        SectionCard(palette = palette) {
            Column {
                val syncHeadlineRaw = when {
                    isSynced -> "Wallet synced"
                    targetHeight == 0L -> "Connecting to node"
                    state.refreshInProgress && lastScanned == restoreHeight -> "Scanning blockchain"
                    state.refreshInProgress && blocksPerSecSmoothed <= 0.0 -> "Syncing wallet"
                    else -> "Syncing wallet"
                }
                val syncHeadline = if (palette.classic) syncHeadlineRaw.uppercase() else syncHeadlineRaw
                val syncDetail = when {
                    isSynced -> "Scanned to block ${formatGrouped(lastScanned)}"
                    targetHeight == 0L -> "Waiting for network height"
                    state.refreshInProgress && lastScanned == restoreHeight -> "Fetching initial blocks from ${formatGrouped(restoreHeight)}"
                    else -> "${formatGrouped(remainingBlocks)} blocks remaining"
                }

                Row {
                    Icon(
                        imageVector = if (isSynced) Icons.Filled.CheckCircle else Icons.Filled.Sync,
                        contentDescription = syncHeadline,
                        tint = if (isSynced) palette.success else palette.accent
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        syncHeadline,
                        color = iosPrimaryText,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        fontFamily = chromeFont,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(syncDetail, color = iosSecondary)
                Spacer(Modifier.height(10.dp))

                KeyValueRow(if (palette.classic) "NODE" else "Node", state.nodeUrl ?: walletManager.defaultNodeUrl(), labelColor = iosSecondary, valueColor = iosPrimaryText)
                KeyValueRow(if (palette.classic) "SCANNED" else "Scanned", formatGrouped(lastScanned), labelColor = iosSecondary, valueColor = iosPrimaryText)
                if (targetHeight > 0L) {
                    KeyValueRow(if (palette.classic) "NETWORK HEIGHT" else "Network Height", formatGrouped(targetHeight), labelColor = iosSecondary, valueColor = iosPrimaryText)
                    KeyValueRow(if (palette.classic) "PROGRESS" else "Progress", progressPercentText, labelColor = iosSecondary, valueColor = iosPrimaryText)
                }
                if (!isSynced) {
                    KeyValueRow(if (palette.classic) "REMAINING" else "Remaining", "${formatGrouped(remainingBlocks)} blocks", labelColor = iosSecondary, valueColor = iosPrimaryText)
                }
                if (state.refreshInProgress && blocksPerSecSmoothed > 0.0) {
                    KeyValueRow(if (palette.classic) "THROUGHPUT" else "Throughput", String.format("%.1f blk/s", blocksPerSecSmoothed), labelColor = iosSecondary, valueColor = iosPrimaryText)
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
        Spacer(Modifier.height(16.dp))

        Text(
            if (palette.classic) "RECENT TRANSACTIONS" else "Recent Transactions",
            color = iosPrimaryText,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            fontFamily = chromeFont,
        )
        Spacer(Modifier.height(8.dp))

        SectionCard(palette = palette) {
            Column {
                if (transfersSorted.isEmpty()) {
                    Text("No transactions yet.", color = iosSecondary)
                } else {
                    transfersSorted.forEachIndexed { index, t ->
                        TransferRow(
                            t = t,
                            palette = palette,
                            onClick = {
                                selectedTransfer = t
                                showTransferDetails = true
                            }
                        )
                        if (index != transfersSorted.lastIndex) {
                            Spacer(Modifier.height(2.dp))
                            androidx.compose.material3.HorizontalDivider(color = palette.separator)
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Actions row
        if (state.refreshInProgress) {
            Row(modifier = Modifier.fillMaxWidth()) {
                SecondaryActionButton(
                    text = "Refreshing…",
                    onClick = { },
                    palette = palette,
                    enabled = false,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                SecondaryActionButton(
                    text = "Cancel",
                    onClick = {
                        walletManager.cancelRefresh()
                        statusText = "Cancel requested"
                    },
                    palette = palette,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            PrimaryActionButton(
                text = if (palette.classic) "REFRESH WALLET" else "Refresh Wallet",
                onClick = {
                    errorText = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                walletManager.refreshWallet()
                            }
                            walletManager.refreshWalletDataSnapshots()
                            statusText = "Refreshed"
                        } catch (t: Throwable) {
                            errorText = t.message ?: t.javaClass.simpleName
                        }
                    }
                },
                palette = palette,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Text("Use Send and Receive for payments and new addresses.", color = iosSecondary)
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
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TransferRow(
    t: Transfer,
    palette: NexaPalette,
    onClick: () -> Unit,
) {
    val direction = if (palette.classic) t.directionLabel().uppercase() else t.directionLabel()
    val amountColor = when (t.direction.lowercase()) {
        "in" -> palette.success
        "out" -> palette.danger
        else -> palette.primaryText
    }

    val relTime = TimeFormat.relative(t.timestamp)
    val statusText = when {
        t.pending && palette.classic -> "PENDING"
        t.pending -> "Pending"
        else -> "${formatGrouped(t.confirmations)} conf"
    }
    val shortTxid = if (t.txid.length > 18) "${t.txid.take(10)}…${t.txid.takeLast(6)}" else t.txid
    val directionIcon = when (t.direction.lowercase()) {
        "in" -> Icons.Filled.ArrowDownward
        "out" -> Icons.Filled.ArrowUpward
        else -> Icons.Filled.Sync
    }
    val amountText = XmrFormat.formatPiconeroAsDisplayXmr(t.amount)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = directionIcon,
                contentDescription = direction,
                tint = amountColor,
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    direction,
                    color = palette.primaryText,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = if (palette.classic) FontFamily.Monospace else FontFamily.Default,
                )

                Spacer(Modifier.height(4.dp))

                Row {
                    relTime?.let {
                        Text(it, color = palette.secondaryText)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(statusText, color = palette.secondaryText)
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    shortTxid,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = palette.secondaryText,
                    fontSize = 12.sp
                )
            }

            Column {
                Text(
                    "$amountText XMR",
                    fontFamily = FontFamily.Monospace,
                    color = amountColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp
                )
                t.fee?.let {
                    Text(
                        "Fee ${XmrFormat.formatPiconeroAsDisplayXmr(it)}",
                        fontFamily = FontFamily.Monospace,
                        color = palette.secondaryText,
                        fontSize = 12.sp
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
                // themed below via palette from parent if available
                Button(
                    onClick = {
                        scope.launch {
                            ClipboardCompat.setText(clipboard, t.txid)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF39FF14),
                        contentColor = Color(0xFF001A12),
                    )
                ) {
                    Text("Copy TXID")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF121612),
                    contentColor = Color(0xFF39FF14),
                )
            ) { Text("Close") }
        }
    )
}

/**
 * Receive screen with persisted receive subaddresses, QR, copy, and share.
 */
@Composable
private fun ReceiveScreen(walletManager: WalletManager, palette: NexaPalette) {
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

    val qrBitmap = remember(paymentUri, palette.classic, palette.accent, palette.background) {
        runCatching {
            if (paymentUri.isEmpty()) {
                null
            } else if (palette.classic) {
                MoneroQr.qrBitmap(
                    paymentUri,
                    sizePx = 640,
                    foreground = palette.accent.toArgb(),
                    background = palette.background.toArgb(),
                )
            } else {
                MoneroQr.qrBitmap(paymentUri, sizePx = 640)
            }
        }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        ScreenHeading(
            title = if (palette.classic) "RECEIVE" else "Receive",
            subtitle = "Show the QR code, copy the address, or create a fresh receive address for better privacy.",
            palette = palette
        )
        Spacer(Modifier.height(12.dp))

        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "Monero receive QR",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(
                        if (palette.classic) palette.background else Color.White,
                        RoundedCornerShape(if (palette.classic) 4.dp else 12.dp),
                    )
                    .then(
                        if (palette.classic) {
                            Modifier.border(1.dp, palette.border, RoundedCornerShape(4.dp))
                        } else {
                            Modifier
                        }
                    )
            )
        } else {
            Text("QR unavailable", color = palette.secondaryText)
        }

        Spacer(Modifier.height(12.dp))

        SectionLabel("Address", palette)
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Text(
                receiveAddress.ifBlank { "(unavailable)" },
                fontFamily = FontFamily.Monospace,
                color = palette.primaryText,
            )
        }

        if (paymentUri.isNotBlank() && paymentUri.startsWith("monero:")) {
            Spacer(Modifier.height(12.dp))
            SectionLabel("Payment URI", palette)
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(
                    paymentUri, 
                    fontFamily = FontFamily.Monospace, 
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        PrimaryActionButton(
            text = "Copy",
            palette = palette,
            onClick = {
                scope.launch {
                    ClipboardCompat.setText(clipboard, if (paymentUri.isNotBlank()) paymentUri else receiveAddress)
                    statusText = "Copied"
                }
            },
            enabled = receiveAddress.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        SecondaryActionButton(
            text = "Share",
            onClick = {
                if (paymentUri.isBlank() || qrBitmap == null) {
                    statusText = "Nothing to share yet"
                    return@SecondaryActionButton
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
            palette = palette,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        SectionLabel("Receive address", palette)
        Spacer(Modifier.height(6.dp))

        if (receiveEntries.isEmpty()) {
            Text("Loading receive addresses…", color = Color.Gray)
        } else {
            Text(
                receiveEntries.firstOrNull { it.subaddressIndex == selectedSubaddressIndex }?.let {
                    val label = it.label.trim()
                    if (label.isEmpty()) "Selected: Subaddress ${it.subaddressIndex}" else "Selected: $label"
                } ?: "Selected: Subaddress $selectedSubaddressIndex",
                color = palette.primaryText,
            )

            Spacer(Modifier.height(8.dp))

            receiveEntries.forEach { entry ->
                val title = entry.label.trim().ifEmpty { "Subaddress ${entry.subaddressIndex}" }
                SecondaryActionButton(
                    text = if (entry.subaddressIndex == selectedSubaddressIndex) "Selected: $title" else title,
                    onClick = { selectedSubaddressIndex = entry.subaddressIndex },
                    palette = palette,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(8.dp))

            PrimaryActionButton(
                text = "New receive address",
                onClick = { showCreatePrompt = true },
                palette = palette,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(16.dp))

        SectionLabel("Payment request (optional)", palette)
        Spacer(Modifier.height(6.dp))

        OutlinedTextField(
            value = amountXmr,
            onValueChange = { amountXmr = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("0.0 XMR", color = palette.secondaryText) },
            colors = nexaFieldColors(palette),
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("What is this for?", color = palette.secondaryText) },
            colors = nexaFieldColors(palette),
        )

        statusText?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = palette.primaryText)
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
                    placeholder = { Text("Optional label", color = palette.secondaryText) },
                    colors = nexaFieldColors(palette),
                )
            },
            confirmButton = {
                PrimaryActionButton(
                    text = "Create",
                    onClick = {
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
                    },
                    palette = palette,
                )
            },
            dismissButton = {
                SecondaryActionButton(
                    text = "Cancel",
                    onClick = {
                        newLabel = ""
                        showCreatePrompt = false
                    },
                    palette = palette,
                )
            }
        )
    }
}

/**
 * Send screen: fee preview, send, and send max (sweep) using WalletManager.
 */
@Composable
private fun SendScreen(walletManager: WalletManager, palette: NexaPalette) {
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
    var showExactConfirmation by remember { mutableStateOf(false) }
    var showMaxConfirmation by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var sendFromSubaddressEnabled by remember { mutableStateOf(false) }
    var fromSubaddressMinor by remember { mutableStateOf(0) }
    var receiveEntries by remember { mutableStateOf<List<ReceiveSubaddressEntry>>(emptyList()) }
    var subaddressUnlockedOverride by remember { mutableStateOf<Long?>(null) }

    val unlockedPiconero = if (sendFromSubaddressEnabled) {
        subaddressUnlockedOverride ?: 0L
    } else {
        state.balance?.unlockedPiconero ?: 0L
    }
    val unlockedXmr = XmrFormat.formatPiconeroAsDisplayXmr(unlockedPiconero)
    val hasWallet = !state.walletId.isNullOrBlank()
    val configSnapshot = remember(context, state.nodeUrl) { MoneroConfig.snapshot(context) }
    val broadcastNodeUrl = remember(context, state.nodeUrl, configSnapshot) {
        MoneroConfig.broadcastNodeUrl(context, walletManager.currentNodeUrl())
    }
    val selectedSubaddressTitle = receiveEntries.firstOrNull { it.subaddressIndex == fromSubaddressMinor }
        ?.let { entry ->
            entry.label.trim().ifEmpty { "Subaddress ${entry.subaddressIndex}" }
        }
        ?: "Subaddress $fromSubaddressMinor"

    fun canPreviewFee(): Boolean = hasWallet && toAddress.trim().isNotEmpty() && amountXmrText.trim().isNotEmpty() && !isEstimating && !isSending
    fun amountPiconeroOrNull(): Long? = runCatching { parseXmrToPiconero(amountXmrText) }.getOrNull()
    fun hasUnlockedForExactSend(): Boolean {
        val amount = amountPiconeroOrNull() ?: return false
        val fee = estimatedFee?.fee ?: return false
        if (amount < 0L || fee < 0L) return false
        if (amount > unlockedPiconero) return false
        return fee <= unlockedPiconero - amount
    }
    fun canSendExact(): Boolean = canPreviewFee() && estimatedFee != null && hasUnlockedForExactSend()
    fun canSendMax(): Boolean = hasWallet && toAddress.trim().isNotEmpty() && !isEstimating && !isSending
    fun totalWithFeeText(): String? {
        val fee = estimatedFee ?: return null
        val amount = amountPiconeroOrNull() ?: return null
        return XmrFormat.formatPiconeroAsXmr(amount + fee.fee)
    }

    LaunchedEffect(hasWallet) {
        if (!hasWallet) return@LaunchedEffect
        runCatching { walletManager.loadReceiveSubaddressBook() }
            .onSuccess { book ->
                receiveEntries = book.entries
                if (book.entries.none { it.subaddressIndex == fromSubaddressMinor }) {
                    fromSubaddressMinor = 0
                }
            }
            .onFailure { errorText = it.message ?: it.javaClass.simpleName }
    }

    LaunchedEffect(sendFromSubaddressEnabled, fromSubaddressMinor, hasWallet) {
        if (!hasWallet || !sendFromSubaddressEnabled) {
            subaddressUnlockedOverride = null
            return@LaunchedEffect
        }

        runCatching { walletManager.getBalance(fromSubaddressMinor) }
            .onSuccess { bal ->
                subaddressUnlockedOverride = bal.unlockedPiconero
            }
            .onFailure {
                subaddressUnlockedOverride = null
                errorText = it.message ?: it.javaClass.simpleName
            }
    }

    Column(modifier = Modifier.fillMaxSize().background(palette.background).verticalScroll(rememberScrollState()).padding(16.dp)) {
        ScreenHeading(
            title = if (palette.classic) "SEND" else "Send",
            subtitle = "Enter the recipient and amount, preview the fee, then confirm the transaction.",
            palette = palette
        )
        Spacer(Modifier.height(8.dp))

        if (unlockedPiconero > 0L) {
            Text("Unlocked balance: $unlockedXmr XMR", color = palette.secondaryText)
            Spacer(Modifier.height(12.dp))
        }

        Text("To address", color = palette.primaryText)
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
                .padding(bottom = 8.dp),
            colors = nexaFieldColors(palette),
            trailingIcon = {
                androidx.compose.material3.IconButton(onClick = { showScanner = true }) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = "Scan QR code",
                        tint = palette.accent,
                    )
                }
            }
        )

        Text("Amount (XMR)", color = palette.primaryText)
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
                .padding(bottom = 8.dp),
            colors = nexaFieldColors(palette),
        )

        val mergedError = errorText ?: state.lastError
        if (mergedError != null) {
            Text(mergedError, color = palette.danger)
            Spacer(Modifier.height(12.dp))
        }

        infoText?.let {
            Text(it)
            Spacer(Modifier.height(12.dp))
        }

        estimatedFee?.let { fee ->
            SectionLabel("Confirm", palette)
            Spacer(Modifier.height(6.dp))
            Text("Estimated fee: ${fee.feeXmr} XMR", color = palette.primaryText)
            totalWithFeeText()?.let { total ->
                Text("Total (amount + fee): $total XMR", color = palette.secondaryText)
            }
            if (!hasUnlockedForExactSend()) {
                Spacer(Modifier.height(6.dp))
                Text("Insufficient unlocked balance for amount + fee.", color = palette.danger)
            }
            Text(
                toAddress.trim(),
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = palette.secondaryText
            )
            Spacer(Modifier.height(12.dp))
        }

        sweepPreview?.let { preview ->
            SectionLabel("Confirm send max", palette)
            Spacer(Modifier.height(6.dp))
            Text("Send max amount: ${preview.amountXmr} XMR", color = palette.primaryText)
            Text("Estimated fee: ${preview.feeXmr} XMR", color = palette.secondaryText)
            Spacer(Modifier.height(12.dp))
        }

        sendResult?.let { result ->
            SectionLabel("Sent", palette)
            Text("TXID: ${result.txid}", fontFamily = FontFamily.Monospace)
            Text("Fee: ${result.feeXmr} XMR")
            Spacer(Modifier.height(12.dp))
        }

        sweepResult?.let { result ->
            SectionLabel("Sent max", palette)
            Text("TXID: ${result.txid}", fontFamily = FontFamily.Monospace)
            Text("Amount: ${result.amountXmr} XMR")
            Text("Fee: ${result.feeXmr} XMR")
            Spacer(Modifier.height(12.dp))
        }

        SectionLabel("Actions", palette)
        Spacer(Modifier.height(8.dp))

        SecondaryActionButton(
            text = if (isEstimating) "Estimating..." else "Preview fee",
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
                        estimatedFee = if (sendFromSubaddressEnabled) {
                            walletManager.previewFee(
                                fromSubaddressMinor = fromSubaddressMinor,
                                destinations = listOf(
                                    SendJson.Destination(
                                        address = toAddress.trim(),
                                        amount = amountPiconero
                                    )
                                )
                            )
                        } else {
                            walletManager.previewFee(
                                destinations = listOf(
                                    SendJson.Destination(
                                        address = toAddress.trim(),
                                        amount = amountPiconero
                                    )
                                )
                            )
                        }
                        infoText = if (sendFromSubaddressEnabled) {
                            "Fee estimated using $selectedSubaddressTitle."
                        } else {
                            "Fee estimated successfully."
                        }
                    } catch (t: Throwable) {
                        errorText = t.message ?: t.javaClass.simpleName
                    } finally {
                        isEstimating = false
                    }
                }
            },
            enabled = canPreviewFee(),
            palette = palette,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        PrimaryActionButton(
            text = if (isSending) "Sending..." else "Send",
            onClick = {
                showExactConfirmation = true
            },
            enabled = canSendExact(),
            palette = palette,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        SecondaryActionButton(
            text = if (isPreviewingMax) "Estimating max..." else "Preview send max",
            onClick = {
                errorText = null
                infoText = null
                sendResult = null
                sweepResult = null
                estimatedFee = null
                scope.launch {
                    isPreviewingMax = true
                    try {
                        sweepPreview = if (sendFromSubaddressEnabled) {
                            walletManager.previewSweep(
                                fromSubaddressMinor = fromSubaddressMinor,
                                toAddress = toAddress.trim()
                            )
                        } else {
                            walletManager.previewSweep(toAddress = toAddress.trim())
                        }
                        infoText = if (sendFromSubaddressEnabled) {
                            "Maximum sendable amount estimated using $selectedSubaddressTitle."
                        } else {
                            "Maximum sendable amount estimated."
                        }
                    } catch (t: Throwable) {
                        errorText = t.message ?: t.javaClass.simpleName
                    } finally {
                        isPreviewingMax = false
                    }
                }
            },
            enabled = canSendMax(),
            palette = palette,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        PrimaryActionButton(
            text = if (isSending) "Sending..." else "Send max",
            onClick = {
                showMaxConfirmation = true
            },
            enabled = canSendMax(),
            palette = palette,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        SectionLabel("Advanced", palette)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Send from specific subaddress", color = palette.primaryText, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Constrain inputs to one receive subaddress for tighter spend control.", color = palette.secondaryText)
            }
            Switch(
                checked = sendFromSubaddressEnabled,
                onCheckedChange = {
                    sendFromSubaddressEnabled = it
                    estimatedFee = null
                    sweepPreview = null
                    sendResult = null
                    sweepResult = null
                    infoText = null
                    errorText = null
                },
                colors = nexaSwitchColors(palette),
            )
        }
        if (sendFromSubaddressEnabled) {
            Spacer(Modifier.height(12.dp))
            Text("Selected subaddress", color = palette.primaryText)
            Spacer(Modifier.height(6.dp))
            if (receiveEntries.isEmpty()) {
                Text("Loading subaddresses…", color = palette.secondaryText)
            } else {
                receiveEntries.forEach { entry ->
                    val title = entry.label.trim().ifEmpty { "Subaddress ${entry.subaddressIndex}" }
                    SecondaryActionButton(
                        text = if (entry.subaddressIndex == fromSubaddressMinor) "Selected: $title" else title,
                        onClick = {
                            fromSubaddressMinor = entry.subaddressIndex
                            estimatedFee = null
                            sweepPreview = null
                            sendResult = null
                            sweepResult = null
                            infoText = null
                            errorText = null
                        },
                        palette = palette,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text("This constrains inputs to account 0, subaddress $fromSubaddressMinor.", color = palette.secondaryText)
            }
        }

        Spacer(Modifier.height(12.dp))
        KeyValueRow(
            label = "Policy",
            value = when (configSnapshot.networkPolicy) {
                MoneroConfig.NetworkPolicy.CLEARNET -> "Clearnet"
                MoneroConfig.NetworkPolicy.I2P -> "I2P only"
                MoneroConfig.NetworkPolicy.HYBRID -> "Hybrid"
            },
            labelColor = palette.secondaryText,
            valueColor = palette.primaryText
        )
        Spacer(Modifier.height(6.dp))
        KeyValueRow(
            label = "Broadcast",
            value = broadcastNodeUrl,
            labelColor = palette.secondaryText,
            valueColor = palette.primaryText
        )
        if (!configSnapshot.i2pHttpProxyAddress.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            KeyValueRow(
                label = "I2P Proxy",
                value = configSnapshot.i2pHttpProxyAddress,
                labelColor = palette.secondaryText,
                valueColor = palette.primaryText
            )
        }
    }

    if (showExactConfirmation) {
        val amountPiconero = amountPiconeroOrNull()
        AlertDialog(
            onDismissRequest = { if (!isSending) showExactConfirmation = false },
            title = { Text("Confirm Send") },
            text = {
                Column {
                    Text("To", color = palette.secondaryText)
                    Text(toAddress.trim(), fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    amountPiconero?.let {
                        Text("Amount", color = palette.secondaryText)
                        Text("${XmrFormat.formatPiconeroAsXmr(it)} XMR", fontFamily = FontFamily.Monospace)
                    }
                    estimatedFee?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("Fee", color = palette.secondaryText)
                        Text("${it.feeXmr} XMR", fontFamily = FontFamily.Monospace)
                    }
                    totalWithFeeText()?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("Total", color = palette.secondaryText)
                        Text("$it XMR", fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                PrimaryActionButton(
                    text = if (isSending) "Sending..." else "Confirm Send",
                    palette = palette,
                    enabled = !isSending,
                    onClick = {
                        if (isSending) return@PrimaryActionButton
                        // Disable immediately so dismiss+launch cannot race a second send.
                        isSending = true
                        showExactConfirmation = false
                        errorText = null
                        infoText = null
                        sendResult = null
                        sweepResult = null
                        scope.launch {
                            try {
                                val amountPiconeroNow = parseXmrToPiconero(amountXmrText)
                                val feePiconero = estimatedFee?.fee ?: 0L
                                if (amountPiconeroNow > unlockedPiconero ||
                                    feePiconero > unlockedPiconero - amountPiconeroNow
                                ) {
                                    errorText = "Insufficient unlocked balance for amount + fee."
                                    return@launch
                                }

                                if (MoneroConfig.requireDeviceAuth(context)) {
                                    val activity = context as? ComponentActivity
                                        ?: throw IllegalStateException("Device authentication requires an activity context")
                                    DeviceAuthGate.authenticate(
                                        activity = activity,
                                        title = "Confirm send",
                                        subtitle = "Authenticate to send Monero"
                                    )
                                }

                                sendResult = if (sendFromSubaddressEnabled) {
                                    walletManager.send(
                                        fromSubaddressMinor = fromSubaddressMinor,
                                        toAddress = toAddress.trim(),
                                        amountPiconero = amountPiconeroNow
                                    )
                                } else {
                                    walletManager.send(
                                        toAddress = toAddress.trim(),
                                        amountPiconero = amountPiconeroNow
                                    )
                                }
                                infoText = if (sendFromSubaddressEnabled) {
                                    "Transaction broadcast from $selectedSubaddressTitle."
                                } else {
                                    "Transaction broadcast."
                                }
                                walletManager.refreshWalletDataSnapshots()
                            } catch (t: Throwable) {
                                errorText = t.message ?: t.javaClass.simpleName
                            } finally {
                                isSending = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                SecondaryActionButton(
                    text = "Cancel",
                    onClick = { showExactConfirmation = false },
                    palette = palette,
                    enabled = !isSending,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }

    if (showMaxConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!isSending) showMaxConfirmation = false },
            title = { Text("Confirm Send Max") },
            text = {
                Column {
                    Text("To", color = palette.secondaryText)
                    Text(toAddress.trim(), fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    sweepPreview?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("Amount", color = palette.secondaryText)
                        Text("${it.amountXmr} XMR", fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(8.dp))
                        Text("Fee", color = palette.secondaryText)
                        Text("${it.feeXmr} XMR", fontFamily = FontFamily.Monospace)
                    } ?: run {
                        Spacer(Modifier.height(8.dp))
                        Text("Preview the max amount before sending.", color = palette.secondaryText)
                    }
                }
            },
            confirmButton = {
                PrimaryActionButton(
                    text = if (isSending) "Sending..." else "Confirm Send Max",
                    palette = palette,
                    onClick = {
                        if (isSending) return@PrimaryActionButton
                        isSending = true
                        showMaxConfirmation = false
                        errorText = null
                        infoText = null
                        sendResult = null
                        scope.launch {
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

                                sweepResult = if (sendFromSubaddressEnabled) {
                                    walletManager.sweep(
                                        fromSubaddressMinor = fromSubaddressMinor,
                                        toAddress = toAddress.trim()
                                    )
                                } else {
                                    walletManager.sweep(toAddress = toAddress.trim())
                                }
                                infoText = if (sendFromSubaddressEnabled) {
                                    "Maximum spendable balance broadcast from $selectedSubaddressTitle."
                                } else {
                                    "Maximum spendable balance broadcast."
                                }
                                walletManager.refreshWalletDataSnapshots()
                            } catch (t: Throwable) {
                                errorText = t.message ?: t.javaClass.simpleName
                            } finally {
                                isSending = false
                            }
                        }
                    },
                    enabled = sweepPreview != null && !isSending,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                SecondaryActionButton(
                    text = "Cancel",
                    onClick = { showMaxConfirmation = false },
                    palette = palette,
                    enabled = !isSending,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }

    fun looksLikeAddress(addr: String): Boolean {
        val s = addr.trim()
        if (s.isEmpty()) return false
        val first = s.first()
        return first == '4' || first == '8' || first == '5'
    }

    fun parseMoneroUri(uri: String) {
        try {
            val trimmed = uri.trim()
            if (!trimmed.lowercase().startsWith("monero:")) {
                errorText = "Invalid payment URI format."
                return
            }

            // Preserve Base58 case: only treat the scheme case-insensitively.
            var remainder = trimmed.substring(trimmed.indexOf(':') + 1)
            if (remainder.startsWith("//")) {
                remainder = remainder.removePrefix("//")
            }

            val address = remainder
                .substringBefore('?')
                .trim('/')
                .trim()

            if (!looksLikeAddress(address)) {
                errorText = "No valid address in payment URI."
                return
            }

            toAddress = address

            if (remainder.contains('?')) {
                val queryString = remainder.substringAfter('?')
                val params = queryString.split("&").associate { param ->
                    val keyValue = param.split("=", limit = 2)
                    keyValue[0].lowercase() to (keyValue.getOrNull(1) ?: "")
                }

                val amountParam = params["amount"] ?: params["tx_amount"]
                amountParam?.let { amountStr ->
                    val amount = amountStr.toDoubleOrNull()
                    if (amount != null && amount >= 0.0) {
                        amountXmrText = String.format("%.12f", amount)
                    }
                }
            }

            infoText = "Payment details loaded from QR code."
        } catch (e: Exception) {
            errorText = "Failed to parse payment URI: ${e.message}"
        }
    }

    fun handleScannedCode(code: String) {
        val trimmed = code.trim()

        if (trimmed.lowercase().startsWith("monero:")) {
            parseMoneroUri(trimmed)
        } else if (looksLikeAddress(trimmed)) {
            toAddress = trimmed
            infoText = "Address loaded from QR code."
        } else {
            errorText = "Invalid QR code. Expected Monero address or payment URI."
        }

        estimatedFee = null
        sweepPreview = null
        sendResult = null
        sweepResult = null
    }

    if (showScanner) {
        QRScannerScreen(
            onScan = { code ->
                showScanner = false
                handleScannedCode(code)
            },
            onDismiss = { showScanner = false }
        )
    }
}

/**
 * Settings screen with editable node URL.
 */
@Composable
private fun SettingsScreen(
    walletManager: WalletManager,
    classicUI: Boolean,
    onClassicUIChange: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by walletManager.state.collectAsState()
    val context = LocalContext.current
    val palette = rememberNexaPalette(classicUI)

    val groupedBg = palette.background
    val cardBg = palette.card
    val secondaryText = palette.secondaryText
    val primaryText = palette.primaryText
    val sectionShape = RoundedCornerShape(if (palette.classic) 4.dp else 16.dp)

    var nodeUrlInput by remember {
        mutableStateOf(state.nodeUrl ?: walletManager.defaultNodeUrl())
    }
    var networkPolicy by remember {
        mutableStateOf(MoneroConfig.networkPolicy(context))
    }
    var i2pRpcInput by remember {
        mutableStateOf(MoneroConfig.i2pRpcAddress(context))
    }
    var i2pProxyInput by remember {
        mutableStateOf(MoneroConfig.i2pHttpProxyAddress(context).orEmpty())
    }

    // Persisted scan tuning (iOS parity)
    var gapLimitInput by remember {
        mutableStateOf(MoneroConfig.gapLimit(context).toString())
    }
    var accountGapInput by remember {
        mutableStateOf(MoneroConfig.accountGap(context).toString())
    }
    var restoreHeightInput by remember {
        mutableStateOf(
            state.syncStatus?.restoreHeight
                ?.takeIf { it > 0L }
                ?.toString()
                ?: ""
        )
    }

    // Validation state (keep messages close to the inputs).
    var gapLimitError by remember { mutableStateOf<String?>(null) }
    var accountGapError by remember { mutableStateOf<String?>(null) }

    var statusText by remember { mutableStateOf<String?>(null) }
    var requireDeviceAuth by remember {
        mutableStateOf(MoneroConfig.requireDeviceAuth(context))
    }
    var showAdvancedRecovery by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(groupedBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            if (palette.classic) "SETTINGS" else "Settings",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = primaryText,
            fontFamily = if (palette.classic) FontFamily.Monospace else FontFamily.Default,
        )
        Spacer(Modifier.height(6.dp))
        Text("Manage node access, device security, and recovery behavior.", color = secondaryText)

        Spacer(Modifier.height(20.dp))

        Text(if (palette.classic) "APPEARANCE" else "Appearance", color = secondaryText)
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = cardBg,
            shape = sectionShape,
            tonalElevation = if (palette.classic) 0.dp else 1.dp,
            shadowElevation = 0.dp,
            border = if (palette.classic) BorderStroke(1.dp, palette.border) else null,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Classic UI", color = primaryText, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Standard non-neon look. Leave off for the neon terminal theme (default).",
                            color = secondaryText
                        )
                    }
                    Switch(
                        checked = classicUI,
                        onCheckedChange = {
                            onClassicUIChange(it)
                            statusText = if (it) "Enabled Classic UI" else "Disabled Classic UI"
                        },
                        colors = nexaSwitchColors(palette),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(if (palette.classic) "NETWORK" else "Network", color = secondaryText)
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = cardBg,
            shape = sectionShape,
            tonalElevation = if (palette.classic) 0.dp else 1.dp,
            shadowElevation = 0.dp,
            border = if (palette.classic) BorderStroke(1.dp, palette.border) else null,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Node URL", color = primaryText, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Use your local node, emulator proxy, or a trusted remote node.", color = secondaryText)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = nodeUrlInput,
                    onValueChange = { nodeUrlInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(walletManager.defaultNodeUrl(), color = secondaryText) },
                    colors = nexaFieldColors(palette),
                )
                Spacer(Modifier.height(12.dp))
                PrimaryActionButton(
                    text = "Save node",
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
                    palette = palette,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                KeyValueRow(
                    label = "Current",
                    value = state.nodeUrl ?: walletManager.defaultNodeUrl(),
                    labelColor = secondaryText,
                    valueColor = primaryText
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(if (palette.classic) "NETWORK POLICY & I2P" else "Network Policy & I2P", color = secondaryText)
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = cardBg,
            shape = sectionShape,
            tonalElevation = if (palette.classic) 0.dp else 1.dp,
            shadowElevation = 0.dp,
            border = if (palette.classic) BorderStroke(1.dp, palette.border) else null,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Policy", color = primaryText, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Clearnet uses your daemon above. I2P/hybrid use the I2P RPC node and HTTP proxy for .b32.i2p traffic.",
                    color = secondaryText,
                )
                Spacer(Modifier.height(10.dp))
                listOf(
                    MoneroConfig.NetworkPolicy.CLEARNET to "Clearnet only",
                    MoneroConfig.NetworkPolicy.I2P to "I2P only",
                    MoneroConfig.NetworkPolicy.HYBRID to "Hybrid (scan clearnet, broadcast I2P)",
                ).forEach { (policy, label) ->
                    val selected = networkPolicy == policy
                    SecondaryActionButton(
                        text = if (selected) "✓ $label" else label,
                        onClick = { networkPolicy = policy },
                        palette = palette,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                }

                val i2pEnabled = networkPolicy != MoneroConfig.NetworkPolicy.CLEARNET
                Text("I2P RPC hostname:port", color = primaryText, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = i2pRpcInput,
                    onValueChange = { i2pRpcInput = it },
                    singleLine = true,
                    enabled = i2pEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(MoneroConfig.DEFAULT_I2P_RPC_ADDRESS, color = secondaryText) },
                    colors = nexaFieldColors(palette),
                )
                Spacer(Modifier.height(10.dp))
                Text("I2P HTTP proxy host:port", color = primaryText, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = i2pProxyInput,
                    onValueChange = { i2pProxyInput = it },
                    singleLine = true,
                    enabled = i2pEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("127.0.0.1:4444", color = secondaryText) },
                    colors = nexaFieldColors(palette),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Proxy example: 127.0.0.1:4444 (I2P HTTP proxy). Required for I2P-only and hybrid broadcast.",
                    color = secondaryText,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryActionButton(
                    text = "Save I2P settings",
                    onClick = {
                        MoneroConfig.setNetworkPolicy(context, networkPolicy)
                        MoneroConfig.setI2pRpcAddress(context, i2pRpcInput.trim().ifEmpty { null })
                        MoneroConfig.setI2pHttpProxyAddress(context, i2pProxyInput.trim().ifEmpty { null })
                        statusText = "Saved I2P settings"
                    },
                    palette = palette,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(if (palette.classic) "SECURITY" else "Security", color = secondaryText)
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = cardBg,
            shape = sectionShape,
            tonalElevation = if (palette.classic) 0.dp else 1.dp,
            shadowElevation = 0.dp,
            border = if (palette.classic) BorderStroke(1.dp, palette.border) else null,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Require device auth", color = primaryText, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("Use biometrics or device credentials for wallet access and sending.", color = secondaryText)
                    }
                    Switch(
                        checked = requireDeviceAuth,
                        onCheckedChange = {
                            requireDeviceAuth = it
                            MoneroConfig.setRequireDeviceAuth(context, it)
                            statusText = if (it) "Enabled device auth" else "Disabled device auth"
                        },
                        colors = nexaSwitchColors(palette),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (DeviceAuthGate.isAvailable(context)) {
                        "Biometric or device credential authentication is available on this device."
                    } else {
                        "Biometric or device credential authentication is not currently available on this device."
                    },
                    color = secondaryText
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(if (palette.classic) "MAINTENANCE" else "Maintenance", color = secondaryText)
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = cardBg,
            shape = sectionShape,
            tonalElevation = if (palette.classic) 0.dp else 1.dp,
            shadowElevation = 0.dp,
            border = if (palette.classic) BorderStroke(1.dp, palette.border) else null,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Scan cache", color = primaryText, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Remove the persisted fast-resume cache for this network without deleting the wallet.",
                    color = secondaryText
                )
                Spacer(Modifier.height(12.dp))
                SecondaryActionButton(
                    text = "Clear scan cache (this network)",
                    onClick = {
                        statusText = null
                        scope.launch {
                            try {
                                walletManager.clearScanCache()
                                statusText = "Cleared scan cache for this network"
                            } catch (t: Throwable) {
                                statusText = "Clear scan cache failed: ${t.message ?: t.javaClass.simpleName}"
                            }
                        }
                    },
                    palette = palette,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.walletId.isNullOrBlank(),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(if (palette.classic) "RECOVERY" else "Recovery", color = secondaryText)
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = cardBg,
            shape = sectionShape,
            tonalElevation = if (palette.classic) 0.dp else 1.dp,
            shadowElevation = 0.dp,
            border = if (palette.classic) BorderStroke(1.dp, palette.border) else null,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Restore and rescan", color = primaryText, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Use an earlier height if funds are missing after import, or rescan from zero for a full recovery.",
                    color = secondaryText
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = restoreHeightInput,
                    onValueChange = {
                        restoreHeightInput = it
                        statusText = null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                    placeholder = { Text((state.syncStatus?.restoreHeight ?: 0L).toString(), color = secondaryText) },
                    label = { Text("Restore height") },
                    colors = nexaFieldColors(palette),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This resets scan state and starts over from the height you enter.",
                    color = secondaryText
                )
                Spacer(Modifier.height(12.dp))
                SecondaryActionButton(
                    text = "Rescan from height",
                    onClick = {
                        statusText = null
                        scope.launch {
                            val height = parseRestoreHeightInput(restoreHeightInput)
                            if (height == null) {
                                statusText = "Enter a valid restore height"
                                return@launch
                            }

                            try {
                                statusText = "Rescanning from $height"
                                walletManager.rescanFromHeight(height)
                                statusText = "Rescan completed from $height"
                            } catch (t: Throwable) {
                                statusText = "Rescan failed: ${t.message ?: t.javaClass.simpleName}"
                            }
                        }
                    },
                    palette = palette,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.walletId.isNullOrBlank() && !state.refreshInProgress,
                )
                Spacer(Modifier.height(8.dp))
                SecondaryActionButton(
                    text = "Full rescan (from block 0)",
                    onClick = {
                        restoreHeightInput = "0"
                        statusText = null
                        scope.launch {
                            try {
                                statusText = "Running full rescan from 0"
                                walletManager.rescanFromHeight(0L)
                                statusText = "Full rescan completed"
                            } catch (t: Throwable) {
                                statusText = "Full rescan failed: ${t.message ?: t.javaClass.simpleName}"
                            }
                        }
                    },
                    palette = palette,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.walletId.isNullOrBlank() && !state.refreshInProgress,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryActionButton(
                    text = "Apply recovery settings",
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
                    palette = palette,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Advanced recovery", color = primaryText, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("Only change scan lookahead if an import appears incomplete after using the right restore height.", color = secondaryText)
                    }
                    SecondaryActionButton(
                        text = if (showAdvancedRecovery) "Hide" else "Show",
                        onClick = { showAdvancedRecovery = !showAdvancedRecovery },
                        palette = palette,
                        modifier = Modifier.height(40.dp),
                    )
                }

                if (showAdvancedRecovery) {
                    Spacer(Modifier.height(12.dp))

                    Text("Gap limit (subaddresses per account)", color = primaryText)
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
                        placeholder = { Text(MoneroConfig.DEFAULT_GAP_LIMIT.toString(), color = secondaryText) },
                        colors = nexaFieldColors(palette),
                    )
                    Text(
                        gapLimitError ?: "Valid range: 1..100000 (default ${MoneroConfig.DEFAULT_GAP_LIMIT})",
                        color = if (gapLimitError != null) palette.danger else secondaryText
                    )

                    Spacer(Modifier.height(12.dp))

                    Text("Accounts (lookahead)", color = primaryText)
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
                        placeholder = { Text(MoneroConfig.DEFAULT_ACCOUNT_GAP.toString(), color = secondaryText) },
                        colors = nexaFieldColors(palette),
                    )
                    Text(
                        accountGapError ?: "Valid range: 1..1000 (default ${MoneroConfig.DEFAULT_ACCOUNT_GAP})",
                        color = if (accountGapError != null) palette.danger else secondaryText
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "Effective gapLimit=${MoneroConfig.gapLimit(context)} accountGap=${MoneroConfig.accountGap(context)}",
                        color = secondaryText
                    )
                }
            }
        }

        statusText?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                color = primaryText,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardBg, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            )
        }

        Spacer(Modifier.height(24.dp))
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

private fun parseRestoreHeightInput(raw: String): Long? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return trimmed.toLongOrNull()?.takeIf { it >= 0L }
}
