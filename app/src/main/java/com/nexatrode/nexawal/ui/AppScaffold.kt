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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
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
import com.nexatrode.nexawal.BuildConfig
import com.nexatrode.nexawal.DeviceAuthGate
import com.nexatrode.nexawal.FiatTxSnapshot
import com.nexatrode.nexawal.MoneroConfig
import com.nexatrode.nexawal.MoneroQr
import com.nexatrode.nexawal.R
import com.nexatrode.nexawal.WalletManager.ReceiveSubaddressEntry
import com.nexatrode.nexawal.SendJson
import com.nexatrode.nexawal.TimeFormat
import com.nexatrode.nexawal.Transfer
import com.nexatrode.nexawal.logic.FiatEstimate
import com.nexatrode.nexawal.logic.FiatRate
import com.nexatrode.nexawal.logic.MoneroPaymentUri
import com.nexatrode.nexawal.logic.NetworkRouting
import com.nexatrode.nexawal.logic.XmrAmount
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
    val isLight: Boolean,
)

@Composable
internal fun rememberNexaPalette(technoTheme: Boolean): NexaPalette {
    val dark = isSystemInDarkTheme()
    // Techno Theme ON = neon terminal look (`palette.classic == true`).
    // OFF (default) = standard non-neon look.
    val neon = technoTheme
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
                    isLight = false,
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
                    isLight = true,
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
                isLight = !dark,
            )
        }
    }
}

@Composable
private fun ScreenHeading(
    title: String,
    palette: NexaPalette,
    subtitle: String? = null,
) {
    Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = palette.primaryText)
    if (!subtitle.isNullOrBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = palette.secondaryText, fontSize = 15.sp, lineHeight = 21.sp)
    }
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
internal fun rememberAppPalette(technoTheme: Boolean = MoneroConfig.isTechnoThemeEnabled(LocalContext.current)): NexaPalette {
    return rememberNexaPalette(technoTheme)
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
    icon: ImageVector? = null,
) {
    val neon = palette.classic
    val container = if (neon) palette.cta else Color(0xFFFF6B35)
    val content = if (neon) palette.ctaText else Color.White
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 54.dp),
        shape = RoundedCornerShape(if (neon) 28.dp else 14.dp),
        border = if (neon) BorderStroke(1.dp, palette.border.copy(alpha = 0.35f)) else null,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = if (neon) palette.cta.copy(alpha = if (palette.isLight) 0.45f else 0.35f) else Color(0xFFFF6B35).copy(alpha = 0.4f),
            disabledContentColor = if (neon) palette.ctaText.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.7f),
        )
    ) {
        val textColor = if (enabled) content else (if (neon) palette.secondaryText.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.7f))
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            color = textColor,
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
    icon: ImageVector? = null,
) {
    val neon = palette.classic
    val lightNeon = neon && palette.isLight
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 54.dp),
        shape = RoundedCornerShape(if (lightNeon || !neon) 14.dp else 28.dp),
        border = BorderStroke(if (lightNeon) 1.5.dp else 1.dp, if (neon) palette.border else palette.separator),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.secondaryAction,
            contentColor = if (neon) palette.accent else palette.primaryText,
            disabledContainerColor = palette.secondaryAction.copy(alpha = if (lightNeon) 0.7f else 0.5f),
            // Neon disabled text: bumped from ~0.45 to improve contrast against secondaryAction background.
            disabledContentColor = if (neon) palette.secondaryText.copy(alpha = 0.65f) else palette.primaryText.copy(alpha = 0.5f),
        )
    ) {
        val textColor = if (enabled) {
            if (neon) palette.accent else palette.primaryText
        } else {
            if (neon) palette.secondaryText.copy(alpha = 0.65f) else palette.primaryText.copy(alpha = 0.5f)
        }
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            color = textColor,
            fontWeight = FontWeight.Medium,
            fontFamily = if (neon) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
internal fun DangerActionButton(
    text: String,
    onClick: () -> Unit,
    palette: NexaPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val neon = palette.classic
    val lightNeon = neon && palette.isLight
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(if (neon) 4.dp else 12.dp),
        border = if (neon) BorderStroke(if (lightNeon) 1.5.dp else 2.dp, palette.danger) else null,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (neon) Color.Transparent else palette.danger.copy(alpha = 0.9f),
            contentColor = if (neon) palette.danger else Color.White,
            disabledContainerColor = if (neon) Color.Transparent else palette.danger.copy(alpha = 0.4f),
            disabledContentColor = if (neon) palette.danger.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.7f),
        )
    ) {
        val textColor = if (enabled) {
            if (neon) palette.danger else Color.White
        } else {
            if (neon) palette.danger.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.7f)
        }
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (neon) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1,
        )
    }
}

/**
 * Wallet home Send / Receive — matches iOS WalletView:
 * - Techno: clear fill, neon border box (4dp), accent label
 * - Standard: filled (Send orange / Receive green), 12dp corners, white label
 */
@Composable
private fun WalletHomeActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    palette: NexaPalette,
    modifier: Modifier = Modifier,
    fillColor: Color,
) {
    val neon = palette.classic
    val container = if (neon) Color.Transparent else fillColor
    val content = if (neon) palette.accent else Color.White
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(if (neon) 4.dp else 12.dp),
        border = if (neon) BorderStroke(2.dp, palette.border) else null,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
        ),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            color = content,
            fontWeight = FontWeight.SemiBold,
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
    var technoTheme by remember { mutableStateOf(MoneroConfig.isTechnoThemeEnabled(context)) }
    val palette = rememberNexaPalette(technoTheme)
    val items = listOf(
        BottomNavItem.Wallet,
        BottomNavItem.Receive,
        BottomNavItem.Send,
        BottomNavItem.Settings,
    )

    val scaffoldModifier = modifier.fillMaxSize()

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
        modifier = scaffoldModifier,
        containerColor = palette.background,
        bottomBar = {
            NavigationBar(
                modifier = Modifier.semantics { testTag = A11yTags.BOTTOM_NAV },
                containerColor = palette.card,
                contentColor = palette.secondaryText
            ) {
                items.forEach { item ->
                    val selected = currentDestination
                        ?.hierarchy
                        ?.any { it.route == item.route } == true
                    val label = stringResource(item.labelRes)

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
                                contentDescription = null,
                                tint = if (selected) palette.accent else palette.secondaryText
                            )
                        },
                        label = {
                            Text(
                                if (palette.classic) label.uppercase() else label,
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
                    technoTheme = technoTheme,
                    onTechnoThemeChange = { enabled ->
                        technoTheme = enabled
                        MoneroConfig.setTechnoThemeEnabled(context, enabled)
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
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    data object Wallet : BottomNavItem(
        route = "wallet",
        labelRes = R.string.nav_wallet,
        icon = Icons.Filled.Home
    )

    data object Send : BottomNavItem(
        route = "send",
        labelRes = R.string.nav_send,
        icon = Icons.AutoMirrored.Filled.Send
    )

    data object Receive : BottomNavItem(
        route = "receive",
        labelRes = R.string.nav_receive,
        icon = Icons.Filled.QrCode
    )

    data object Settings : BottomNavItem(
        route = "settings",
        labelRes = R.string.nav_settings,
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by walletManager.state.collectAsState()
    val fiatRate by walletManager.fiatPrices.displayRate.collectAsState()
    val scroll = rememberScrollState()
    var errorText by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var syncDetailsExpanded by remember {
        mutableStateOf(MoneroConfig.syncDetailsExpanded(context))
    }

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
    // Tip must be a real daemon height (not preflight chainHeight ≈ restoreHeight).
    val tipKnown = chainHeight > restoreHeight || (state.syncStatus?.chainTime ?: 0L) > 0L
    val targetHeight = when {
        tipKnown && state.refreshTargetHeight != null -> state.refreshTargetHeight!!
        tipKnown -> chainHeight
        else -> 0L
    }

    val remainingBlocks = if (targetHeight > 0L) (targetHeight - lastScanned).coerceAtLeast(0L) else 0L
    val syncTolerance = 3L
    val scanInterrupted = remember(state.refreshInProgress, lastScanned, targetHeight) {
        MoneroConfig.scanInterrupted(context)
    }
    val trustedScanned = remember(state.refreshInProgress, lastScanned, targetHeight, scanInterrupted) {
        MoneroConfig.trustedScannedHeight(context)
    }
    // Match iOS: near tip alone is not enough after cancel/quit — require a clean checkpoint.
    val emptyHistoryAtTip =
        targetHeight > restoreHeight + 10_000L && state.transfers.isEmpty()
    val isSynced =
        !state.refreshInProgress &&
            !scanInterrupted &&
            targetHeight > 0L &&
            lastScanned + syncTolerance >= targetHeight &&
            lastScanned <= trustedScanned + syncTolerance &&
            !emptyHistoryAtTip

    // Session-average blocks/sec for this refresh:
    // (lastScanned - baseline at refresh start) / wall time since refresh start.
    // Avoids EMA/burst spikes when a large get_blocks batch lands at once.
    // Also track a trailing ~30s window so mid-sync stalls are visible as "recent".
    var sessionRateStartMs by remember { mutableStateOf<Long?>(null) }
    var sessionRateStartScanned by remember { mutableStateOf(0L) }
    var blocksPerSecSession by remember { mutableStateOf(0.0) }
    var recentRateSamples by remember { mutableStateOf(listOf<Pair<Long, Long>>()) }
    var blocksPerSecRecent by remember { mutableStateOf(0.0) }
    val recentRateWindowMs = 30_000L

    LaunchedEffect(state.refreshInProgress, state.refreshStartedAtMs) {
        if (state.refreshInProgress) {
            sessionRateStartMs = state.refreshStartedAtMs ?: System.currentTimeMillis()
            sessionRateStartScanned = lastScanned
            blocksPerSecSession = 0.0
            recentRateSamples = emptyList()
            blocksPerSecRecent = 0.0
        }
        // Keep final session average after refresh completes until the next refresh starts.
    }

    LaunchedEffect(state.refreshInProgress, lastScanned, sessionRateStartMs, sessionRateStartScanned) {
        val startMs = sessionRateStartMs ?: return@LaunchedEffect
        if (!state.refreshInProgress && blocksPerSecSession > 0.0) return@LaunchedEffect
        val now = System.currentTimeMillis()
        val elapsedMs = (now - startMs).coerceAtLeast(1L)
        val scanned = (lastScanned - sessionRateStartScanned).coerceAtLeast(0L)
        if (scanned > 0L && elapsedMs >= 500L) {
            blocksPerSecSession = (scanned.toDouble() * 1000.0) / elapsedMs.toDouble()
        }

        if (state.refreshInProgress && scanned > 0L) {
            val pruned = (recentRateSamples + (now to lastScanned))
                .filter { now - it.first <= recentRateWindowMs }
            recentRateSamples = pruned
            if (pruned.size >= 2) {
                val first = pruned.first()
                val last = pruned.last()
                val dtMs = (last.first - first.first).coerceAtLeast(1L)
                val db = (last.second - first.second).coerceAtLeast(0L)
                if (db > 0L && dtMs >= 500L) {
                    blocksPerSecRecent = (db.toDouble() * 1000.0) / dtMs.toDouble()
                }
            }
        }
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
    val progressPercentText = stringResource(R.string.progress_pct_fmt, progress * 100f)

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
            // Same-height tie-break: txid A→Z (matches iOS).
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
                        if (palette.classic) "nexawal" else stringResource(R.string.total_balance),
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
                    ApproxFiatLine(totalPiconero, fiatRate, iosSecondary)

                    if (showUnlockedBalance) {
                        Spacer(Modifier.height(14.dp))

                        val unlockedLabel = stringResource(R.string.label_unlocked)
                        Text(
                            if (palette.classic) unlockedLabel.uppercase() else unlockedLabel,
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
                        ApproxFiatLine(unlockedPiconero, fiatRate, iosSecondary)
                    }

                    if (state.balanceIsStaleWhileSyncing) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.balance_updating),
                            color = iosSecondary
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    val sendLabel = stringResource(R.string.nav_send)
                    val receiveLabel = stringResource(R.string.nav_receive)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        WalletHomeActionButton(
                            text = if (palette.classic) sendLabel.uppercase() else sendLabel,
                            icon = Icons.AutoMirrored.Filled.Send,
                            onClick = onOpenSend,
                            palette = palette,
                            fillColor = Color(0xFFE67E22).copy(alpha = 0.9f),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        WalletHomeActionButton(
                            text = if (palette.classic) receiveLabel.uppercase() else receiveLabel,
                            icon = Icons.Filled.QrCode,
                            onClick = onOpenReceive,
                            palette = palette,
                            fillColor = Color(0xFF34C759).copy(alpha = 0.9f),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Sync details (no STATUS section title)
        SectionCard(palette = palette) {
            Column {
                val hasNodeError = mergedError != null && !state.refreshInProgress && !isSynced
                val isStallError = hasNodeError && isSyncStallError(state.syncStalled)
                // Treat sync as effectively not-complete for display when a refresh error exists,
                // so we never imply "synced" alongside an unreachable/failed node.
                val isSyncedEffective = isSynced && mergedError == null
                val showSyncProgress = !isSyncedEffective || state.refreshInProgress
                val syncHeadlineRaw = when {
                    isStallError -> stringResource(R.string.sync_stalled)
                    hasNodeError -> stringResource(R.string.sync_node_unreachable)
                    isSyncedEffective -> stringResource(R.string.sync_wallet_synced)
                    targetHeight == 0L -> stringResource(R.string.sync_connecting)
                    state.refreshInProgress && lastScanned == restoreHeight -> stringResource(R.string.sync_scanning)
                    state.refreshInProgress && blocksPerSecSession <= 0.0 -> stringResource(R.string.sync_syncing)
                    else -> stringResource(R.string.sync_syncing)
                }
                val syncHeadline = if (palette.classic) syncHeadlineRaw.uppercase() else syncHeadlineRaw
                val syncDetail = when {
                    isStallError -> stringResource(R.string.sync_stalled_action)
                    hasNodeError -> mergedError!!.let { if (it.length > 120) it.take(120) + "…" else it }
                    isSyncedEffective -> stringResource(R.string.scanned_to_block_fmt, formatGrouped(lastScanned))
                    targetHeight == 0L -> stringResource(R.string.sync_waiting_height)
                    state.refreshInProgress && lastScanned == restoreHeight -> stringResource(R.string.fetching_initial_fmt, formatGrouped(restoreHeight))
                    else -> stringResource(R.string.blocks_remaining_fmt, formatGrouped(remainingBlocks))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val next = !syncDetailsExpanded
                            syncDetailsExpanded = next
                            MoneroConfig.setSyncDetailsExpanded(context, next)
                        }
                        .a11yPoliteStatus(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (isSyncedEffective) Icons.Filled.CheckCircle else Icons.Filled.Sync,
                        contentDescription = null,
                        tint = if (isSyncedEffective) palette.success else palette.accent
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        syncHeadline,
                        color = iosPrimaryText,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        fontFamily = chromeFont,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (syncDetailsExpanded) {
                            stringResource(R.string.a11y_hide_sync_details)
                        } else {
                            stringResource(R.string.a11y_show_sync_details)
                        },
                        tint = palette.accent,
                        modifier = Modifier.rotate(if (syncDetailsExpanded) 90f else 0f),
                    )
                }

                AnimatedVisibility(visible = syncDetailsExpanded) {
                    Column {
                        Spacer(Modifier.height(4.dp))
                        Text(syncDetail, color = iosSecondary)
                    }
                }

                if (showSyncProgress) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .a11ySyncProgress(progress, stringResource(R.string.a11y_sync_progress_fmt, (progress * 100).toInt())),
                        color = iosBlue,
                        trackColor = iosSeparator,
                    )
                }

                AnimatedVisibility(visible = syncDetailsExpanded) {
                    Column {
                        Spacer(Modifier.height(10.dp))

                        val nodeLabel = stringResource(R.string.label_node)
                        val scannedLabel = stringResource(R.string.label_scanned)
                        val networkHeightLabel = stringResource(R.string.label_network_height)
                        val progressLabel = stringResource(R.string.label_progress)
                        val remainingLabel = stringResource(R.string.label_remaining)
                        val avgThroughputLabel = stringResource(R.string.label_throughput_avg)
                        val recentThroughputLabel = stringResource(R.string.label_throughput_recent)
                        KeyValueRow(if (palette.classic) nodeLabel.uppercase() else nodeLabel, walletManager.nodeAddressForDisplay(state.nodeUrl ?: walletManager.defaultNodeUrl()), labelColor = iosSecondary, valueColor = iosPrimaryText)
                        KeyValueRow(if (palette.classic) scannedLabel.uppercase() else scannedLabel, formatGrouped(lastScanned), labelColor = iosSecondary, valueColor = iosPrimaryText)
                        if (targetHeight > 0L) {
                            KeyValueRow(if (palette.classic) networkHeightLabel.uppercase() else networkHeightLabel, formatGrouped(targetHeight), labelColor = iosSecondary, valueColor = iosPrimaryText)
                            KeyValueRow(if (palette.classic) progressLabel.uppercase() else progressLabel, progressPercentText, labelColor = iosSecondary, valueColor = iosPrimaryText)
                        }
                        if (!isSyncedEffective) {
                            KeyValueRow(if (palette.classic) remainingLabel.uppercase() else remainingLabel, stringResource(R.string.blocks_value_fmt, formatGrouped(remainingBlocks)), labelColor = iosSecondary, valueColor = iosPrimaryText)
                        }
                        if (blocksPerSecSession > 0.0) {
                            KeyValueRow(
                                if (palette.classic) avgThroughputLabel.uppercase() else avgThroughputLabel,
                                stringResource(R.string.blocks_per_sec_fmt, blocksPerSecSession),
                                labelColor = iosSecondary,
                                valueColor = iosPrimaryText,
                            )
                        }
                        if (blocksPerSecRecent > 0.0) {
                            KeyValueRow(
                                if (palette.classic) recentThroughputLabel.uppercase() else recentThroughputLabel,
                                stringResource(R.string.blocks_per_sec_fmt, blocksPerSecRecent),
                                labelColor = iosSecondary,
                                valueColor = iosPrimaryText,
                            )
                        }
                    }
                }

            }
        }
        Spacer(Modifier.height(16.dp))

        SectionCard(palette = palette) {
            Column {
                val recentTransactionsLabel = stringResource(R.string.recent_transactions)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (palette.classic) recentTransactionsLabel.uppercase() else recentTransactionsLabel,
                        color = iosPrimaryText,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        fontFamily = chromeFont,
                        modifier = Modifier.weight(1f),
                    )
                    if (transfersSorted.isNotEmpty()) {
                        Text(
                            transfersSorted.size.toString(),
                            color = iosSecondary,
                            fontFamily = chromeFont,
                            fontSize = 13.sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                if (transfersSorted.isEmpty()) {
                    Text(stringResource(R.string.no_transactions_yet), color = iosSecondary)
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

        // Refresh / cancel — match iOS boxed outline + red cancel, equal height
        val cancelRequestedText = stringResource(R.string.cancel_requested)
        val refreshWalletLabel = stringResource(R.string.refresh_wallet)
        val refreshingLabel = stringResource(R.string.refreshing)
        val refreshedText = stringResource(R.string.refreshed)
        val actionHeight = 52.dp
        Row(modifier = Modifier.fillMaxWidth()) {
            val refreshShape = RoundedCornerShape(if (palette.classic) 4.dp else 12.dp)
            Button(
                onClick = {
                    if (state.refreshInProgress) return@Button
                    errorText = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                walletManager.refreshWallet()
                            }
                            walletManager.refreshWalletDataSnapshots()
                            statusText = refreshedText
                        } catch (t: Throwable) {
                            errorText = t.message ?: t.javaClass.simpleName
                        }
                    }
                },
                enabled = !state.refreshInProgress,
                modifier = Modifier
                    .weight(1f)
                    .height(actionHeight),
                shape = refreshShape,
                border = if (palette.classic) {
                    BorderStroke(2.dp, palette.border.copy(alpha = if (state.refreshInProgress) 0.4f else 1f))
                } else {
                    null
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (palette.classic) Color.Transparent else Color(0xFF007AFF),
                    contentColor = if (palette.classic) palette.accent else Color.White,
                    disabledContainerColor = if (palette.classic) Color.Transparent else Color(0xFF007AFF).copy(alpha = 0.45f),
                    disabledContentColor = if (palette.classic) palette.accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.7f),
                ),
            ) {
                val refreshContent = if (state.refreshInProgress) {
                    if (palette.classic) palette.accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.7f)
                } else {
                    if (palette.classic) palette.accent else Color.White
                }
                if (state.refreshInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = refreshContent,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = refreshContent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.refreshInProgress) {
                        if (palette.classic) refreshingLabel.uppercase() else refreshingLabel
                    } else {
                        if (palette.classic) refreshWalletLabel.uppercase() else refreshWalletLabel
                    },
                    color = refreshContent,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = if (palette.classic) FontFamily.Monospace else FontFamily.Default,
                    maxLines = 1,
                )
            }

            if (state.refreshInProgress) {
                Spacer(Modifier.width(12.dp))
                DangerActionButton(
                    text = if (palette.classic) {
                        stringResource(R.string.action_cancel).uppercase()
                    } else {
                        stringResource(R.string.action_cancel)
                    },
                    icon = Icons.Filled.Cancel,
                    onClick = {
                        walletManager.cancelRefresh()
                        statusText = cancelRequestedText
                    },
                    palette = palette,
                    modifier = Modifier.weight(1f),
                )
            }
        }

                statusText?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Color.Gray, modifier = Modifier.a11yPoliteStatus())
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
                    .a11yAssertiveError()
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showTransferDetails && selectedTransfer != null) {
        TransferDetailsDialog(
            t = selectedTransfer!!,
            snapshot = walletManager.fiatPrices.snapshots.snapshot(selectedTransfer!!.txid),
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .a11yKeyValue(label, value),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = labelColor,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            value,
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun TransferRow(
    t: Transfer,
    palette: NexaPalette,
    onClick: () -> Unit,
) {
    val directionRaw = when (t.direction.lowercase()) {
        "in" -> stringResource(R.string.direction_received)
        "out" -> stringResource(R.string.direction_sent)
        "self" -> stringResource(R.string.direction_self)
        else -> t.direction
    }
    val direction = if (palette.classic) directionRaw.uppercase() else directionRaw
    val amountColor = when (t.direction.lowercase()) {
        "in" -> palette.success
        "out" -> palette.danger
        else -> palette.primaryText
    }

    val relTime = TimeFormat.relative(t.timestamp)
    val pendingLabel = stringResource(R.string.status_pending)
    val statusText = when {
        t.pending && palette.classic -> pendingLabel.uppercase()
        t.pending -> pendingLabel
        else -> stringResource(R.string.confirmations_fmt, formatGrouped(t.confirmations))
    }
    val shortTxid = if (t.txid.length > 18) "${t.txid.take(10)}…${t.txid.takeLast(6)}" else t.txid
    val directionIcon = when (t.direction.lowercase()) {
        "in" -> Icons.Filled.ArrowDownward
        "out" -> Icons.Filled.ArrowUpward
        else -> Icons.Filled.Sync
    }
    val amountText = XmrFormat.formatPiconeroAsDisplayXmr(t.amount)
    // Direction must not be conveyed by color alone: prefix the amount with a sign glyph
    // (in addition to the icon + direction label already shown).
    val amountSign = when (t.direction.lowercase()) {
        "in" -> "+ "
        "out" -> "\u2212 " // unicode minus, visually distinct from a hyphen
        else -> ""
    }
    val signedAmountText = "$amountSign$amountText"
    val summary = stringResource(R.string.a11y_transfer_row_fmt, directionRaw, signedAmountText, statusText)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = summary
                testTag = A11yTags.TRANSFER_ROW
            }
            .padding(vertical = 12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = directionIcon,
                contentDescription = null,
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
                    stringResource(R.string.xmr_unit_fmt, signedAmountText),
                    fontFamily = FontFamily.Monospace,
                    color = amountColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp
                )
                t.fee?.let {
                    Text(
                        stringResource(R.string.fee_value_fmt, XmrFormat.formatPiconeroAsDisplayXmr(it)),
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
    snapshot: FiatTxSnapshot?,
    onDismiss: () -> Unit,
) {
    val absTime = TimeFormat.absolute(t.timestamp)
    val clipboard = ClipboardCompat.current()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val perXmr = snapshot?.let { FiatEstimate.decimalOrNull(it.fiatPerXmr) }
    val snapCurrency = snapshot?.currency
    val directionLabel = when (t.direction.lowercase()) {
        "in" -> stringResource(R.string.direction_received)
        "out" -> stringResource(R.string.direction_sent)
        "self" -> stringResource(R.string.direction_self)
        else -> t.direction
    }
    val statusLabel = if (t.pending) stringResource(R.string.status_pending) else stringResource(R.string.status_confirmed)
    val dash = "—"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transaction)) },
        text = {
            Column {
                Text(stringResource(R.string.tx_type_fmt, directionLabel), fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.tx_status_fmt, statusLabel), fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.tx_amount_fmt, t.amountXmr()), fontFamily = FontFamily.Monospace)
                if (perXmr != null && snapCurrency != null) {
                    Text(
                        FiatEstimate.recordedApproxText(t.amount, perXmr, snapCurrency),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = Color.Gray,
                    )
                }
                t.fee?.let { fee ->
                    Text(stringResource(R.string.tx_fee_fmt, XmrFormat.formatPiconeroAsXmr(fee)), fontFamily = FontFamily.Monospace)
                    if (perXmr != null && snapCurrency != null) {
                        Text(
                            FiatEstimate.recordedApproxText(fee, perXmr, snapCurrency),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = Color.Gray,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.tx_height_fmt, t.height?.toString() ?: dash), fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.tx_confirmations_fmt, t.confirmations.toString()), fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.tx_time_fmt, absTime ?: dash), fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(stringResource(R.string.tx_txid_fmt, t.txid), fontFamily = FontFamily.Monospace)
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
                    Text(stringResource(R.string.copy_txid))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val uri = android.net.Uri.parse("https://xmrchain.net/tx/${t.txid}")
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF121612),
                        contentColor = Color(0xFF39FF14),
                    )
                ) {
                    Text(stringResource(R.string.open_in_explorer))
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
            ) { Text(stringResource(R.string.action_close)) }
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
    val fiatRate by walletManager.fiatPrices.displayRate.collectAsState()

    var receiveEntries by remember { mutableStateOf<List<ReceiveSubaddressEntry>>(emptyList()) }
    var selectedSubaddressIndex by remember { mutableStateOf(0) }
    var receiveAddress by remember { mutableStateOf("") }
    var amountXmr by remember { mutableStateOf("") }
    var amountInputMode by remember { mutableStateOf(AmountInputMode.XMR) }
    var description by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf<String?>(null) }
    var showCreatePrompt by remember { mutableStateOf(false) }
    var newLabel by remember { mutableStateOf("") }
    var addressMenuOpen by remember { mutableStateOf(false) }

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
            amountXmr = AmountUnitParsing.xmrAmountForUri(amountXmr, amountInputMode, fiatRate),
            description = description.trim().takeIf { it.isNotEmpty() },
        )
    } else {
        ""
    }

    val qrBitmap = remember(paymentUri, palette.classic, palette.isLight, palette.accent, palette.background, palette.primaryText) {
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
            } else if (!palette.isLight) {
                MoneroQr.qrBitmap(
                    paymentUri,
                    sizePx = 640,
                    foreground = palette.primaryText.toArgb(),
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
        val receiveTitle = stringResource(R.string.receive_xmr_title)
        ScreenHeading(
            title = if (palette.classic) receiveTitle.uppercase() else receiveTitle,
            palette = palette,
        )
        Spacer(Modifier.height(12.dp))

        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.receive_qr_cd),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = 320.dp)
                    .aspectRatio(1f)
                    .semantics { testTag = A11yTags.RECEIVE_QR }
                    .background(
                        when {
                            palette.classic || !palette.isLight -> palette.background
                            else -> Color.White
                        },
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
            Text(stringResource(R.string.qr_unavailable), color = palette.secondaryText)
        }

        Spacer(Modifier.height(12.dp))

        val uriShape = RoundedCornerShape(if (palette.classic) 4.dp else 8.dp)
        SelectionContainer {
            Text(
                paymentUri.ifBlank { stringResource(R.string.address_unavailable) },
                fontFamily = FontFamily.Monospace,
                color = palette.primaryText,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.card, uriShape)
                    .then(
                        if (palette.classic) {
                            Modifier.border(1.dp, palette.border, uriShape)
                        } else {
                            Modifier
                        }
                    )
                    .padding(12.dp),
            )
        }

        Spacer(Modifier.height(16.dp))

        SectionCard(palette = palette) {
            val paymentRequestTitle = stringResource(R.string.payment_request_optional)
            Text(
                if (palette.classic) paymentRequestTitle.uppercase() else paymentRequestTitle,
                color = palette.primaryText,
                fontWeight = FontWeight.Bold,
                fontFamily = if (palette.classic) FontFamily.Monospace else FontFamily.Default,
            )
            Spacer(Modifier.height(12.dp))

            AmountUnitField(
                text = amountXmr,
                onTextChange = { amountXmr = it },
                mode = amountInputMode,
                onModeChange = { amountInputMode = it },
                rate = fiatRate,
                palette = palette,
                label = stringResource(R.string.label_amount),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.description_label)) },
                placeholder = { Text(stringResource(R.string.receive_desc_ph), color = palette.secondaryText) },
                colors = nexaFieldColors(palette),
            )
        }

        Spacer(Modifier.height(12.dp))

        val addressCopiedText = stringResource(R.string.address_copied_short)
        val nothingToShareText = stringResource(R.string.nothing_to_share)
        val shareChooserTitle = stringResource(R.string.share_payment_link)
        val shareFailedFmt = stringResource(R.string.share_failed_fmt)

        SecondaryActionButton(
            text = stringResource(R.string.share_payment_link),
            icon = Icons.Filled.Share,
            onClick = {
                if (paymentUri.isBlank() || qrBitmap == null) {
                    statusText = nothingToShareText
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
                    context.startActivity(Intent.createChooser(shareIntent, shareChooserTitle))
                }.onFailure {
                    statusText = String.format(shareFailedFmt, it.message ?: it.javaClass.simpleName)
                }
            },
            enabled = paymentUri.isNotBlank() && qrBitmap != null,
            palette = palette,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        PrimaryActionButton(
            text = stringResource(R.string.copy_address),
            icon = Icons.Filled.ContentCopy,
            palette = palette,
            onClick = {
                scope.launch {
                    ClipboardCompat.setText(clipboard, receiveAddress)
                    statusText = addressCopiedText
                }
            },
            enabled = receiveAddress.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        val receiveAddressHeading = stringResource(R.string.receive_address_heading)
        Text(
            if (palette.classic) receiveAddressHeading.uppercase() else receiveAddressHeading,
            color = palette.primaryText,
            fontWeight = FontWeight.Bold,
            fontFamily = if (palette.classic) FontFamily.Monospace else FontFamily.Default,
        )
        Spacer(Modifier.height(8.dp))

        if (receiveEntries.isEmpty()) {
            Text(stringResource(R.string.loading_receive_addresses), color = palette.secondaryText)
        } else {
            val subaddressFmt = stringResource(R.string.subaddress_fmt)
            val selectedEntry = receiveEntries.firstOrNull { it.subaddressIndex == selectedSubaddressIndex }
            val selectedTitle = selectedEntry?.label?.trim().orEmpty().ifEmpty {
                String.format(subaddressFmt, selectedSubaddressIndex)
            }

            Box {
                SecondaryActionButton(
                    text = selectedTitle,
                    onClick = { addressMenuOpen = true },
                    palette = palette,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            role = Role.DropdownList
                        },
                )
                androidx.compose.material3.DropdownMenu(
                    expanded = addressMenuOpen,
                    onDismissRequest = { addressMenuOpen = false },
                ) {
                    receiveEntries.forEach { entry ->
                        val title = entry.label.trim().ifEmpty { String.format(subaddressFmt, entry.subaddressIndex) }
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(title) },
                            onClick = {
                                selectedSubaddressIndex = entry.subaddressIndex
                                addressMenuOpen = false
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            SecondaryActionButton(
                text = stringResource(R.string.new_address),
                icon = Icons.Filled.AddCircle,
                onClick = { showCreatePrompt = true },
                palette = palette,
                modifier = Modifier.fillMaxWidth()
            )
        }

        statusText?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = palette.primaryText, modifier = Modifier.a11yPoliteStatus())
        }
    }

    if (showCreatePrompt) {
        AlertDialog(
            onDismissRequest = { showCreatePrompt = false },
            title = { Text(stringResource(R.string.new_address_label_android)) },
            text = {
                OutlinedTextField(
                    value = newLabel,
                    onValueChange = { newLabel = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.optional_label)) },
                    placeholder = { Text(stringResource(R.string.optional_label), color = palette.secondaryText) },
                    colors = nexaFieldColors(palette),
                )
            },
            confirmButton = {
                PrimaryActionButton(
                    text = stringResource(R.string.action_create),
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
                    text = stringResource(R.string.action_cancel),
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
    val fiatRate by walletManager.fiatPrices.displayRate.collectAsState()
    val context = LocalContext.current

    var toAddress by remember { mutableStateOf("") }
    var amountXmrText by remember { mutableStateOf("") }
    var amountInputMode by remember { mutableStateOf(AmountInputMode.XMR) }
    var paymentDescription by remember { mutableStateOf("") }
    var paymentRecipientName by remember { mutableStateOf("") }
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

    val unlockedPiconero = state.balance?.unlockedPiconero ?: 0L
    val unlockedXmr = XmrFormat.formatPiconeroAsDisplayXmr(unlockedPiconero)
    val hasWallet = !state.walletId.isNullOrBlank()

    val insufficientForFeeText = stringResource(R.string.error_insufficient_amount_fee)
    val deviceAuthUnavailableText = stringResource(R.string.device_auth_required_unavailable)
    val activityContextRequiredText = stringResource(R.string.error_activity_context_required)
    val confirmSendTitle = stringResource(R.string.biometric_confirm_send)
    val confirmSendSubtitle = stringResource(R.string.biometric_prompt_send)
    val confirmSendMaxTitle = stringResource(R.string.biometric_confirm_send_max)
    val confirmSendMaxSubtitle = stringResource(R.string.biometric_send_max_subtitle)
    val transactionBroadcastText = stringResource(R.string.transaction_broadcast)
    val maxBalanceBroadcastText = stringResource(R.string.max_balance_broadcast)
    val invalidPaymentUriText = stringResource(R.string.error_invalid_payment_uri)
    val noAddressInUriText = stringResource(R.string.error_no_address_in_uri)
    val paymentFromQrText = stringResource(R.string.info_payment_from_qr)
    val addressFromQrText = stringResource(R.string.info_address_from_qr)
    val invalidQrText = stringResource(R.string.error_invalid_qr)

    fun amountPiconeroOrNull(): Long? = AmountUnitParsing.piconero(amountXmrText, amountInputMode, fiatRate)
    fun canPreviewFee(): Boolean = hasWallet &&
        !state.refreshInProgress &&
        toAddress.trim().isNotEmpty() &&
        amountPiconeroOrNull() != null &&
        !isEstimating &&
        !isSending
    fun hasUnlockedForExactSend(): Boolean {
        val amount = amountPiconeroOrNull() ?: return false
        val fee = estimatedFee?.fee ?: return false
        return com.nexatrode.nexawal.logic.SendSafety.hasUnlockedForExactSend(
            amountPiconero = amount,
            feePiconero = fee,
            unlockedPiconero = unlockedPiconero,
        )
    }
    fun canSendExact(): Boolean = canPreviewFee() && estimatedFee != null && hasUnlockedForExactSend()
    fun canSendMax(): Boolean = hasWallet &&
        !state.refreshInProgress &&
        toAddress.trim().isNotEmpty() &&
        !isEstimating &&
        !isSending
    fun totalWithFeeText(): String? {
        val fee = estimatedFee ?: return null
        val amount = amountPiconeroOrNull() ?: return null
        return XmrFormat.formatPiconeroAsXmr(amount + fee.fee)
    }

    Column(modifier = Modifier.fillMaxSize().background(palette.background).verticalScroll(rememberScrollState()).padding(16.dp)) {
        val sendTitle = stringResource(R.string.send_xmr_title)
        ScreenHeading(
            title = if (palette.classic) sendTitle.uppercase() else sendTitle,
            palette = palette,
        )
        Spacer(Modifier.height(8.dp))

        if (unlockedPiconero > 0L) {
            Text(stringResource(R.string.unlocked_balance_fmt, unlockedXmr), color = palette.secondaryText)
            Spacer(Modifier.height(12.dp))
        }

        Text(stringResource(R.string.to_address), color = palette.primaryText)
        OutlinedTextField(
            value = toAddress,
            onValueChange = { input ->
                val parsed = MoneroPaymentUri.parse(input)
                    ?.takeIf { MoneroPaymentUri.hasCompleteAddressShape(it.address) }
                if (parsed != null) {
                    toAddress = parsed.address
                    parsed.amountXmr
                        ?.let(XmrAmount::parsePiconero)
                        ?.let { pico ->
                            AmountUnitParsing.setXmrPiconero(
                                pico,
                                { amountXmrText = it },
                                { amountInputMode = it },
                            )
                        }
                    paymentDescription = parsed.description.orEmpty()
                    paymentRecipientName = parsed.recipientName.orEmpty()
                } else {
                    toAddress = input
                    paymentDescription = ""
                    paymentRecipientName = ""
                }
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
            label = { Text(stringResource(R.string.to_address)) },
            colors = nexaFieldColors(palette),
            trailingIcon = {
                androidx.compose.material3.IconButton(
                    onClick = { showScanner = true },
                    modifier = Modifier
                        .a11yMinTouchTarget()
                        .semantics { testTag = A11yTags.SCAN_QR },
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = stringResource(R.string.scan_qr_cd),
                        tint = palette.accent,
                    )
                }
            }
        )

        Text(stringResource(R.string.label_amount), color = palette.primaryText)
        AmountUnitField(
            text = amountXmrText,
            onTextChange = {
                amountXmrText = it
                estimatedFee = null
                sendResult = null
                sweepResult = null
                errorText = null
                infoText = null
            },
            mode = amountInputMode,
            onModeChange = { amountInputMode = it },
            rate = fiatRate,
            palette = palette,
            label = stringResource(R.string.label_amount),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )

        if (paymentRecipientName.isNotEmpty() || paymentDescription.isNotEmpty()) {
            SectionLabel(stringResource(R.string.payment_uri_label), palette)
            Spacer(Modifier.height(6.dp))
            if (paymentRecipientName.isNotEmpty()) {
                Text(stringResource(R.string.section_recipient), color = palette.secondaryText)
                Text(paymentRecipientName, color = palette.primaryText)
                Spacer(Modifier.height(6.dp))
            }
            if (paymentDescription.isNotEmpty()) {
                Text(stringResource(R.string.description_label), color = palette.secondaryText)
                Text(paymentDescription, color = palette.primaryText)
                Spacer(Modifier.height(8.dp))
            }
        }

        val mergedError = errorText ?: state.lastError
        if (mergedError != null) {
            Text(mergedError, color = palette.danger, modifier = Modifier.a11yAssertiveError())
            Spacer(Modifier.height(12.dp))
        }

        infoText?.let {
            Text(it, modifier = Modifier.a11yPoliteStatus())
            Spacer(Modifier.height(12.dp))
        }

        estimatedFee?.let { fee ->
            SectionLabel(stringResource(R.string.section_confirm), palette)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.estimated_fee_fmt, fee.feeXmr), color = palette.primaryText)
            ApproxFiatLine(fee.fee, fiatRate, palette.secondaryText)
            totalWithFeeText()?.let { total ->
                Text(stringResource(R.string.total_with_fee_fmt, total), color = palette.secondaryText)
            }
            amountPiconeroOrNull()?.let { amount ->
                ApproxFiatLine(amount + fee.fee, fiatRate, palette.secondaryText)
            }
            if (!hasUnlockedForExactSend()) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.error_insufficient_amount_fee), color = palette.danger, modifier = Modifier.a11yAssertiveError())
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
            SectionLabel(stringResource(R.string.confirm_send_max_section), palette)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.send_max_amount_fmt, preview.amountXmr), color = palette.primaryText)
            ApproxFiatLine(preview.amount, fiatRate, palette.secondaryText)
            Text(stringResource(R.string.estimated_fee_fmt, preview.feeXmr), color = palette.secondaryText)
            ApproxFiatLine(preview.fee, fiatRate, palette.secondaryText)
            Spacer(Modifier.height(12.dp))
        }

        sendResult?.let { result ->
            SectionLabel(stringResource(R.string.section_sent), palette)
            Text(stringResource(R.string.txid_value_fmt, result.txid), fontFamily = FontFamily.Monospace)
            Text(stringResource(R.string.fee_xmr_fmt, result.feeXmr))
            Spacer(Modifier.height(12.dp))
        }

        sweepResult?.let { result ->
            SectionLabel(stringResource(R.string.sent_max_section), palette)
            Text(stringResource(R.string.txid_value_fmt, result.txid), fontFamily = FontFamily.Monospace)
            Text(stringResource(R.string.amount_xmr_fmt, result.amountXmr))
            Text(stringResource(R.string.fee_xmr_fmt, result.feeXmr))
            Spacer(Modifier.height(12.dp))
        }

        SectionLabel(stringResource(R.string.section_actions), palette)
        Spacer(Modifier.height(8.dp))

        val invalidAmountText = stringResource(R.string.invalid_amount)
        val feeEstimatedOkText = stringResource(R.string.fee_estimated_ok)
        val maxAmountEstimatedText = stringResource(R.string.max_amount_estimated)

        SecondaryActionButton(
            text = if (isEstimating) stringResource(R.string.estimating) else stringResource(R.string.preview_fee_android),
            onClick = {
                errorText = null
                infoText = null
                sendResult = null
                sweepResult = null
                sweepPreview = null
                scope.launch {
                    isEstimating = true
                    try {
                        val amountPiconero = amountPiconeroOrNull()
                            ?: throw IllegalArgumentException(invalidAmountText)
                        estimatedFee = walletManager.previewFee(
                            destinations = listOf(
                                SendJson.Destination(
                                    address = toAddress.trim(),
                                    amount = amountPiconero
                                )
                            )
                        )
                        infoText = feeEstimatedOkText
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
            text = if (isSending) stringResource(R.string.sending) else stringResource(R.string.nav_send),
            onClick = {
                showExactConfirmation = true
            },
            enabled = canSendExact(),
            palette = palette,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        SecondaryActionButton(
            text = if (isPreviewingMax) stringResource(R.string.estimating_max) else stringResource(R.string.preview_send_max),
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
                        infoText = maxAmountEstimatedText
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
            text = if (isSending) stringResource(R.string.sending) else stringResource(R.string.send_max_android),
            onClick = {
                showMaxConfirmation = true
            },
            enabled = canSendMax(),
            palette = palette,
            modifier = Modifier.fillMaxWidth()
        )

    }

    if (showExactConfirmation) {
        val amountPiconero = amountPiconeroOrNull()
        val invalidAmountText = stringResource(R.string.invalid_amount)
        AlertDialog(
            onDismissRequest = { if (!isSending) showExactConfirmation = false },
            title = { Text(stringResource(R.string.confirm_send)) },
            text = {
                Column {
                    Text(stringResource(R.string.label_to), color = palette.secondaryText)
                    Text(toAddress.trim(), fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    amountPiconero?.let {
                        Text(stringResource(R.string.label_amount), color = palette.secondaryText)
                        Text(stringResource(R.string.xmr_unit_fmt, XmrFormat.formatPiconeroAsXmr(it)), fontFamily = FontFamily.Monospace)
                        ApproxFiatLine(it, fiatRate, palette.secondaryText)
                    }
                    estimatedFee?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.label_fee), color = palette.secondaryText)
                        Text(stringResource(R.string.xmr_unit_fmt, it.feeXmr), fontFamily = FontFamily.Monospace)
                        ApproxFiatLine(it.fee, fiatRate, palette.secondaryText)
                    }
                    totalWithFeeText()?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.label_total), color = palette.secondaryText)
                        Text(stringResource(R.string.xmr_unit_fmt, it), fontFamily = FontFamily.Monospace)
                        val amount = amountPiconero
                        val fee = estimatedFee?.fee
                        if (amount != null && fee != null) {
                            ApproxFiatLine(amount + fee, fiatRate, palette.secondaryText)
                        }
                    }
                }
            },
            confirmButton = {
                PrimaryActionButton(
                    text = if (isSending) stringResource(R.string.sending) else stringResource(R.string.confirm_send),
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
                                val amountPiconeroNow = amountPiconeroOrNull()
                                    ?: throw IllegalArgumentException(invalidAmountText)
                                val feePiconero = estimatedFee?.fee ?: 0L
                                if (!com.nexatrode.nexawal.logic.SendSafety.hasUnlockedForExactSend(
                                        amountPiconero = amountPiconeroNow,
                                        feePiconero = feePiconero,
                                        unlockedPiconero = unlockedPiconero,
                                    )
                                ) {
                                    errorText = insufficientForFeeText
                                    return@launch
                                }

                                if (MoneroConfig.requireDeviceAuth(context)) {
                                    if (!DeviceAuthGate.isAvailable(context)) {
                                        throw IllegalStateException(deviceAuthUnavailableText)
                                    }
                                    val activity = context as? ComponentActivity
                                        ?: throw IllegalStateException(activityContextRequiredText)
                                    DeviceAuthGate.authenticate(
                                        activity = activity,
                                        title = confirmSendTitle,
                                        subtitle = confirmSendSubtitle
                                    )
                                }

                                sendResult = walletManager.send(
                                    toAddress = toAddress.trim(),
                                    amountPiconero = amountPiconeroNow
                                )
                                infoText = transactionBroadcastText
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
                    text = stringResource(R.string.action_cancel),
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
            title = { Text(stringResource(R.string.confirm_send_max)) },
            text = {
                Column {
                    Text(stringResource(R.string.label_to), color = palette.secondaryText)
                    Text(toAddress.trim(), fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    sweepPreview?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.label_amount), color = palette.secondaryText)
                        Text(stringResource(R.string.xmr_unit_fmt, it.amountXmr), fontFamily = FontFamily.Monospace)
                        ApproxFiatLine(it.amount, fiatRate, palette.secondaryText)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.label_fee), color = palette.secondaryText)
                        Text(stringResource(R.string.xmr_unit_fmt, it.feeXmr), fontFamily = FontFamily.Monospace)
                        ApproxFiatLine(it.fee, fiatRate, palette.secondaryText)
                    } ?: run {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.preview_max_before), color = palette.secondaryText)
                    }
                }
            },
            confirmButton = {
                PrimaryActionButton(
                    text = if (isSending) stringResource(R.string.sending) else stringResource(R.string.confirm_send_max),
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
                                    if (!DeviceAuthGate.isAvailable(context)) {
                                        throw IllegalStateException(deviceAuthUnavailableText)
                                    }
                                    val activity = context as? ComponentActivity
                                        ?: throw IllegalStateException(activityContextRequiredText)
                                    DeviceAuthGate.authenticate(
                                        activity = activity,
                                        title = confirmSendMaxTitle,
                                        subtitle = confirmSendMaxSubtitle
                                    )
                                }

                                sweepResult = walletManager.sweep(toAddress = toAddress.trim())
                                infoText = maxBalanceBroadcastText
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
                    text = stringResource(R.string.action_cancel),
                    onClick = { showMaxConfirmation = false },
                    palette = palette,
                    enabled = !isSending,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }

    fun looksLikeAddress(addr: String): Boolean {
        return MoneroPaymentUri.hasCompleteAddressShape(addr)
    }

    fun parseMoneroUri(uri: String) {
        val parsed = MoneroPaymentUri.parse(uri)
        if (parsed == null) {
            errorText = invalidPaymentUriText
            return
        }
        if (!looksLikeAddress(parsed.address)) {
            errorText = noAddressInUriText
            return
        }

        toAddress = parsed.address
        val amount = parsed.amountXmr
        val pico = amount?.let { XmrAmount.parsePiconero(it) }
        if (pico != null) {
            AmountUnitParsing.setXmrPiconero(pico, { amountXmrText = it }, { amountInputMode = it })
        }
        paymentDescription = parsed.description.orEmpty()
        paymentRecipientName = parsed.recipientName.orEmpty()
        infoText = paymentFromQrText
    }

    fun handleScannedCode(code: String) {
        val trimmed = code.trim()

        if (trimmed.lowercase().startsWith("monero:")) {
            parseMoneroUri(trimmed)
        } else if (looksLikeAddress(trimmed)) {
            toAddress = trimmed
            paymentDescription = ""
            paymentRecipientName = ""
            infoText = addressFromQrText
        } else {
            errorText = invalidQrText
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
    technoTheme: Boolean,
    onTechnoThemeChange: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by walletManager.state.collectAsState()
    val context = LocalContext.current
    val clipboard = ClipboardCompat.current()
    val palette = rememberNexaPalette(technoTheme)

    val groupedBg = palette.background
    val cardBg = palette.card
    val secondaryText = palette.secondaryText
    val primaryText = palette.primaryText
    val sectionShape = RoundedCornerShape(if (palette.classic) 4.dp else 16.dp)

    var nodeUrlInput by remember {
        mutableStateOf(walletManager.nodeAddressForDisplay(state.nodeUrl ?: walletManager.defaultNodeUrl()))
    }

    LaunchedEffect(state.nodeUrl) {
        nodeUrlInput = walletManager.nodeAddressForDisplay(state.nodeUrl ?: walletManager.defaultNodeUrl())
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
    var legalDocument by remember { mutableStateOf<LegalDocument?>(null) }
    val nodeUrlSchemeErrorText = stringResource(R.string.node_url_scheme_error)
    val savedClearnetText = stringResource(R.string.saved_clearnet)
    val savedI2pText = stringResource(R.string.saved_i2p)
    val savedBothText = stringResource(R.string.saved_both)
    val connectingToFmt = stringResource(R.string.connecting_to_fmt)
    val connectingOverI2pText = stringResource(R.string.connecting_over_i2p)
    val connectingHybridText = stringResource(R.string.connecting_hybrid)
    fun applyNetworkSettings(policy: MoneroConfig.NetworkPolicy = networkPolicy) {
        statusText = null
        networkPolicy = policy
        val i2pNode = i2pRpcInput.trim()
        val i2pProxy = i2pProxyInput.trim()
        MoneroConfig.setNetworkPolicy(context, policy)
        MoneroConfig.setI2pRpcAddress(context, i2pNode.ifEmpty { null })
        MoneroConfig.setI2pHttpProxyAddress(context, i2pProxy.ifEmpty { null })

        val clearnetUrl = run {
            var candidate = nodeUrlInput.trim()
            if (candidate.isEmpty()) {
                candidate = walletManager.defaultNodeUrl()
                if (policy != MoneroConfig.NetworkPolicy.I2P) {
                    nodeUrlInput = candidate
                }
            }
            val explicit = NetworkRouting.explicitNodeUrl(candidate)
            if (policy != MoneroConfig.NetworkPolicy.I2P && explicit == null) {
                statusText = nodeUrlSchemeErrorText
                return
            }
            explicit ?: walletManager.defaultNodeUrl()
        }
        if (policy != MoneroConfig.NetworkPolicy.I2P) {
            nodeUrlInput = clearnetUrl
        }
        walletManager.applyNodeAndReconnectInBackground(clearnetUrl)
        walletManager.fiatPrices.settingsDidChange()
        statusText = when {
            state.walletId.isNullOrBlank() && policy == MoneroConfig.NetworkPolicy.CLEARNET -> savedClearnetText
            state.walletId.isNullOrBlank() && policy == MoneroConfig.NetworkPolicy.I2P -> savedI2pText
            state.walletId.isNullOrBlank() -> savedBothText
            policy == MoneroConfig.NetworkPolicy.CLEARNET -> String.format(connectingToFmt, clearnetUrl)
            policy == MoneroConfig.NetworkPolicy.I2P -> connectingOverI2pText
            else -> connectingHybridText
        }
    }
    var requireDeviceAuth by remember {
        mutableStateOf(MoneroConfig.requireDeviceAuth(context))
    }
    var showAdvancedRecovery by remember { mutableStateOf(false) }
    var fiatEnabled by remember { mutableStateOf(MoneroConfig.fiatEstimatesEnabled(context)) }
    var fiatCurrency by remember { mutableStateOf(MoneroConfig.fiatCurrency(context)) }
    var fiatCurrencyMenuOpen by remember { mutableStateOf(false) }

    legalDocument?.let { doc ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { legalDocument = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            LegalDocumentScreen(
                document = doc,
                onClose = { legalDocument = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(groupedBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        val settingsTitle = stringResource(R.string.nav_settings)
        Text(
            if (palette.classic) settingsTitle.uppercase() else settingsTitle,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = primaryText,
            fontFamily = if (palette.classic) FontFamily.Monospace else FontFamily.Default,
        )

        Spacer(Modifier.height(20.dp))

        val appearanceLabel = stringResource(R.string.section_appearance)
        Text(if (palette.classic) appearanceLabel.uppercase() else appearanceLabel, color = secondaryText)
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
                val technoThemeEnabledText = stringResource(R.string.techno_theme_enabled)
                val technoThemeDisabledText = stringResource(R.string.techno_theme_disabled)
                LabeledSwitchRow(
                    label = stringResource(R.string.toggle_techno_theme),
                    checked = technoTheme,
                    onCheckedChange = {
                        onTechnoThemeChange(it)
                        statusText = if (it) technoThemeEnabledText else technoThemeDisabledText
                    },
                    palette = palette,
                    testTag = A11yTags.TECHNO_THEME_SWITCH,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        val howToConnectLabel = stringResource(R.string.section_how_to_connect)
        Text(if (palette.classic) howToConnectLabel.uppercase() else howToConnectLabel, color = secondaryText)
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
                Column(modifier = Modifier.semantics { testTag = A11yTags.NETWORK_POLICY }) {
                    listOf(
                        MoneroConfig.NetworkPolicy.CLEARNET to stringResource(R.string.network_policy_clearnet),
                        MoneroConfig.NetworkPolicy.I2P to stringResource(R.string.network_policy_i2p),
                        MoneroConfig.NetworkPolicy.HYBRID to stringResource(R.string.network_policy_hybrid),
                    ).forEach { (policy, label) ->
                        val selected = networkPolicy == policy
                        SecondaryActionButton(
                            text = if (selected) stringResource(R.string.network_selected_fmt, label) else label,
                            onClick = { applyNetworkSettings(policy) },
                            palette = palette,
                            modifier = Modifier
                                .fillMaxWidth()
                                .a11yRadioOption(selected = selected, label = label),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }

        if (networkPolicy != MoneroConfig.NetworkPolicy.I2P) {
            Spacer(Modifier.height(20.dp))
            val clearnetNodeLabel = stringResource(R.string.section_clearnet_node)
            Text(if (palette.classic) clearnetNodeLabel.uppercase() else clearnetNodeLabel, color = secondaryText)
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
                    OutlinedTextField(
                        value = nodeUrlInput,
                        onValueChange = { nodeUrlInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(walletManager.defaultNodeUrl(), color = secondaryText) },
                        colors = nexaFieldColors(palette),
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { applyNetworkSettings() }),
                    )
                    Spacer(Modifier.height(12.dp))
                    PrimaryActionButton(
                        text = stringResource(R.string.use_this_node),
                        onClick = { applyNetworkSettings() },
                        palette = palette,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (networkPolicy != MoneroConfig.NetworkPolicy.CLEARNET) {
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.section_i2p), color = secondaryText)
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
                    Text(stringResource(R.string.i2p_node_placeholder), color = primaryText, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = i2pRpcInput,
                        onValueChange = { i2pRpcInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.i2p_node_placeholder)) },
                        placeholder = { Text("hostname.b32.i2p:18081", color = secondaryText) },
                        colors = nexaFieldColors(palette),
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { applyNetworkSettings() }),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.i2p_proxy_placeholder), color = primaryText, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = i2pProxyInput,
                        onValueChange = { i2pProxyInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.i2p_proxy_placeholder)) },
                        placeholder = { Text("127.0.0.1:4444", color = secondaryText) },
                        colors = nexaFieldColors(palette),
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { applyNetworkSettings() }),
                    )
                    Spacer(Modifier.height(12.dp))
                    PrimaryActionButton(
                        text = stringResource(R.string.apply_i2p_settings),
                        onClick = { applyNetworkSettings() },
                        palette = palette,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        val securityLabel = stringResource(R.string.section_security)
        Text(if (palette.classic) securityLabel.uppercase() else securityLabel, color = secondaryText)
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
                val deviceAuthEnabledText = stringResource(R.string.device_auth_enabled_status)
                val deviceAuthDisabledText = stringResource(R.string.device_auth_disabled_status)
                LabeledSwitchRow(
                    label = stringResource(R.string.toggle_require_device_auth),
                    checked = requireDeviceAuth,
                    onCheckedChange = {
                        requireDeviceAuth = it
                        MoneroConfig.setRequireDeviceAuth(context, it)
                        statusText = if (it) deviceAuthEnabledText else deviceAuthDisabledText
                    },
                    palette = palette,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        val recoveryLabel = stringResource(R.string.section_recovery)
        Text(if (palette.classic) recoveryLabel.uppercase() else recoveryLabel, color = secondaryText)
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
                    label = { Text(stringResource(R.string.restore_height_placeholder)) },
                    colors = nexaFieldColors(palette),
                )
                Spacer(Modifier.height(12.dp))
                val enterValidRestoreHeightText = stringResource(R.string.enter_valid_restore_height)
                val rescanningFromFmt = stringResource(R.string.rescanning_from_fmt)
                val fullRescanStatusText = stringResource(R.string.full_rescan_status)
                SecondaryActionButton(
                    text = stringResource(R.string.rescan_from_height_android),
                    onClick = {
                        statusText = null
                        val height = parseRestoreHeightInput(restoreHeightInput)
                        if (height == null) {
                            statusText = enterValidRestoreHeightText
                            return@SecondaryActionButton
                        }
                        persistScanTuning(
                            context = context,
                            walletId = state.walletId,
                            gapLimitInput = gapLimitInput,
                            accountGapInput = accountGapInput,
                            onGapError = { gapLimitError = it },
                            onAccountError = { accountGapError = it },
                            onInputsClamped = { gl, ag ->
                                gapLimitInput = gl
                                accountGapInput = ag
                            },
                        )
                        walletManager.rescanFromHeightInBackground(height)
                        statusText = String.format(rescanningFromFmt, height)
                    },
                    palette = palette,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.walletId.isNullOrBlank(),
                )
                Spacer(Modifier.height(8.dp))
                SecondaryActionButton(
                    text = stringResource(R.string.full_rescan_android),
                    onClick = {
                        restoreHeightInput = "0"
                        statusText = null
                        persistScanTuning(
                            context = context,
                            walletId = state.walletId,
                            gapLimitInput = gapLimitInput,
                            accountGapInput = accountGapInput,
                            onGapError = { gapLimitError = it },
                            onAccountError = { accountGapError = it },
                            onInputsClamped = { gl, ag ->
                                gapLimitInput = gl
                                accountGapInput = ag
                            },
                        )
                        walletManager.rescanFromHeightInBackground(0L)
                        statusText = fullRescanStatusText
                    },
                    palette = palette,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.walletId.isNullOrBlank(),
                )

                Spacer(Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.advanced_recovery_android), color = primaryText, fontWeight = FontWeight.SemiBold)
                    }
                    SecondaryActionButton(
                        text = if (showAdvancedRecovery) stringResource(R.string.action_hide) else stringResource(R.string.action_show),
                        onClick = { showAdvancedRecovery = !showAdvancedRecovery },
                        palette = palette,
                        modifier = Modifier.height(40.dp),
                    )
                }

                if (showAdvancedRecovery) {
                    Spacer(Modifier.height(12.dp))

                    Text(stringResource(R.string.gap_limit_android), color = primaryText)
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
                        label = { Text(stringResource(R.string.gap_limit_android)) },
                        placeholder = { Text(MoneroConfig.DEFAULT_GAP_LIMIT.toString(), color = secondaryText) },
                        colors = nexaFieldColors(palette),
                    )
                    Text(
                        gapLimitError ?: stringResource(R.string.gap_range_fmt, MoneroConfig.DEFAULT_GAP_LIMIT),
                        color = if (gapLimitError != null) palette.danger else secondaryText
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(stringResource(R.string.account_gap_android), color = primaryText)
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
                        label = { Text(stringResource(R.string.account_gap_android)) },
                        placeholder = { Text(MoneroConfig.DEFAULT_ACCOUNT_GAP.toString(), color = secondaryText) },
                        colors = nexaFieldColors(palette),
                    )
                    Text(
                        accountGapError ?: stringResource(R.string.account_gap_range_fmt, MoneroConfig.DEFAULT_ACCOUNT_GAP),
                        color = if (accountGapError != null) palette.danger else secondaryText
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        stringResource(R.string.effective_gap_fmt, MoneroConfig.gapLimit(context), MoneroConfig.accountGap(context)),
                        color = secondaryText
                    )
                    Spacer(Modifier.height(12.dp))
                    val savedScanLookaheadText = stringResource(R.string.saved_scan_lookahead)
                    SecondaryActionButton(
                        text = stringResource(R.string.save_scan_lookahead),
                        onClick = {
                            val ok = persistScanTuning(
                                context = context,
                                walletId = state.walletId,
                                gapLimitInput = gapLimitInput,
                                accountGapInput = accountGapInput,
                                onGapError = { gapLimitError = it },
                                onAccountError = { accountGapError = it },
                                onInputsClamped = { gl, ag ->
                                    gapLimitInput = gl
                                    accountGapInput = ag
                                },
                            )
                            statusText = if (ok) savedScanLookaheadText else statusText
                        },
                        palette = palette,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    val clearedScanCacheText = stringResource(R.string.cleared_scan_cache_android)
                    val clearScanCacheFailedFmt = stringResource(R.string.clear_scan_cache_failed_fmt)
                    SecondaryActionButton(
                        text = stringResource(R.string.clear_scan_cache),
                        onClick = {
                            statusText = null
                            scope.launch {
                                try {
                                    walletManager.clearScanCache()
                                    statusText = clearedScanCacheText
                                } catch (t: Throwable) {
                                    statusText = String.format(clearScanCacheFailedFmt, t.message ?: t.javaClass.simpleName)
                                }
                            }
                        },
                        palette = palette,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.walletId.isNullOrBlank(),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        val fiatSectionLabel = stringResource(R.string.section_fiat)
        Text(if (palette.classic) fiatSectionLabel.uppercase() else fiatSectionLabel, color = secondaryText)
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
                val fiatEnabledText = stringResource(R.string.fiat_enabled)
                val fiatDisabledText = stringResource(R.string.fiat_disabled)
                LabeledSwitchRow(
                    label = stringResource(R.string.toggle_show_fiat),
                    description = stringResource(R.string.fiat_help),
                    checked = fiatEnabled,
                    onCheckedChange = {
                        fiatEnabled = it
                        MoneroConfig.setFiatEstimatesEnabled(context, it)
                        fiatCurrency = MoneroConfig.fiatCurrency(context)
                        walletManager.fiatPrices.settingsDidChange()
                        statusText = if (it) fiatEnabledText else fiatDisabledText
                    },
                    palette = palette,
                )
                if (fiatEnabled) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.fiat_currency_label), color = primaryText, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    val currencyCodeNameFmt = stringResource(R.string.currency_code_name_fmt)
                    val currencyMenuExpandedText = stringResource(R.string.a11y_currency_menu_expanded)
                    val currencyMenuCollapsedText = stringResource(R.string.a11y_currency_menu_collapsed)
                    Box {
                        SecondaryActionButton(
                            text = String.format(currencyCodeNameFmt, fiatCurrency, FiatEstimate.currencyNames[fiatCurrency] ?: fiatCurrency),
                            onClick = { fiatCurrencyMenuOpen = true },
                            palette = palette,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    role = Role.DropdownList
                                    contentDescription = if (fiatCurrencyMenuOpen) currencyMenuExpandedText else currencyMenuCollapsedText
                                },
                        )
                        androidx.compose.material3.DropdownMenu(
                            expanded = fiatCurrencyMenuOpen,
                            onDismissRequest = { fiatCurrencyMenuOpen = false },
                        ) {
                            FiatEstimate.supportedCurrencies.forEach { code ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(String.format(currencyCodeNameFmt, code, FiatEstimate.currencyNames[code] ?: code)) },
                                    onClick = {
                                        fiatCurrency = code
                                        MoneroConfig.setFiatCurrency(context, code)
                                        walletManager.fiatPrices.settingsDidChange()
                                        fiatCurrencyMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        val aboutLabel = stringResource(R.string.section_about)
        Text(if (palette.classic) aboutLabel.uppercase() else aboutLabel, color = secondaryText)
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
                Text(
                    stringResource(R.string.app_version_fmt, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString()),
                    color = primaryText,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.about_disclaimer),
                    color = secondaryText,
                )
                Spacer(Modifier.height(12.dp))
                SecondaryActionButton(
                    text = stringResource(R.string.terms_of_use),
                    onClick = { legalDocument = LegalDocument.Terms },
                    palette = palette,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                SecondaryActionButton(
                    text = stringResource(R.string.privacy_policy),
                    onClick = { legalDocument = LegalDocument.Privacy },
                    palette = palette,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                SecondaryActionButton(
                    text = stringResource(R.string.mit_license),
                    onClick = { legalDocument = LegalDocument.License },
                    palette = palette,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.source_on_github),
                    color = secondaryText,
                    fontSize = 13.sp,
                    fontFamily = if (palette.classic) FontFamily.Monospace else FontFamily.Default,
                )
                Spacer(Modifier.height(4.dp))
                val sourceRepoUrl = "https://github.com/nexatrode/nexawal-android"
                val linkCopiedText = stringResource(R.string.link_copied)
                Text(
                    sourceRepoUrl,
                    color = palette.accent,
                    fontFamily = FontFamily.Monospace,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch {
                                ClipboardCompat.setText(clipboard, sourceRepoUrl)
                                statusText = linkCopiedText
                            }
                        },
                )
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
                    .a11yPoliteStatus()
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

private fun parseRestoreHeightInput(raw: String): Long? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return trimmed.toLongOrNull()?.takeIf { it >= 0L }
}

private fun persistScanTuning(
    context: android.content.Context,
    walletId: String?,
    gapLimitInput: String,
    accountGapInput: String,
    onGapError: (String?) -> Unit,
    onAccountError: (String?) -> Unit,
    onInputsClamped: (String, String) -> Unit,
): Boolean {
    onGapError(null)
    onAccountError(null)
    val glRaw = gapLimitInput.trim().toIntOrNull()
    val agRaw = accountGapInput.trim().toIntOrNull()
    if (glRaw == null) {
        onGapError(context.getString(R.string.enter_whole_number))
        return false
    }
    if (agRaw == null) {
        onAccountError(context.getString(R.string.enter_whole_number))
        return false
    }
    val glClamped = glRaw.coerceIn(1, 100_000)
    val agClamped = agRaw.coerceIn(1, 1_000)
    if (glClamped != glRaw || agClamped != agRaw) {
        onInputsClamped(glClamped.toString(), agClamped.toString())
    }
    MoneroConfig.setGapLimit(context, glClamped)
    MoneroConfig.setAccountGap(context, agClamped)
    if (!walletId.isNullOrBlank()) {
        runCatching { WalletCore.setGapLimit(walletId, MoneroConfig.gapLimit(context)) }
        runCatching { WalletCore.setAccountGap(MoneroConfig.accountGap(context)) }
    }
    return true
}

@Composable
private fun ApproxFiatLine(piconero: Long?, rate: FiatRate?, color: Color) {
    val pico = piconero ?: return
    val text = FiatEstimate.liveApproxText(pico, rate, System.currentTimeMillis()) ?: return
    Spacer(Modifier.height(4.dp))
    Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
}
