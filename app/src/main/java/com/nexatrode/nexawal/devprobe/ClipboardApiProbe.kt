package com.nexatrode.nexawal.devprobe

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard

/**
 * ClipboardApiProbe
 *
 * Purpose:
 * - Compile-time probe to determine whether the new Compose clipboard API types exist:
 *   - LocalClipboard
 *   - Clipboard
 *
 * This file is intentionally not referenced by runtime code.
 * Delete it once ClipboardCompat has been migrated.
 */
@Suppress("unused")
private object ClipboardApiProbe {

    @Composable
    fun currentClipboard(): Clipboard = LocalClipboard.current
}
