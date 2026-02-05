package com.nexatrode.nexawal.ui

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString

/**
 * ClipboardCompat
 *
 * Centralized clipboard access for the app UI, migrated to the new suspend-friendly Compose
 * clipboard API:
 * - LocalClipboard
 * - Clipboard (suspend getClipEntry / setClipEntry)
 *
 * Plain-text helpers are used for:
 * - wallet address
 * - monero URI
 * - txid
 *
 * NOTE: On Android 13+ the system shows clipboard UI when content is copied. Avoid duplicating
 * toasts/snackbars for copy actions on newer Android versions if it becomes noisy.
 */
object ClipboardCompat {

    /**
     * Current Clipboard instance (new API).
     */
    @Composable
    fun current(): Clipboard = LocalClipboard.current

    /**
     * Copy plain text to clipboard.
     *
     * This is suspend because Clipboard.setClipEntry is suspend.
     */
    suspend fun setText(clipboard: Clipboard, text: String) {
        val clipData = ClipData.newPlainText("text", text)
        clipboard.setClipEntry(ClipEntry(clipData))
    }

    /**
     * Copy plain text to clipboard and mark it as sensitive, so Android 13+ clipboard preview
     * can avoid showing the content.
     *
     * Use this only for secrets (mnemonic, private keys). Do NOT mark normal public values
     * like addresses/txids as sensitive.
     */
    suspend fun setSensitiveText(clipboard: Clipboard, text: String) {
        val clipData = ClipData.newPlainText("sensitive", text)

        val extrasKey = if (Build.VERSION.SDK_INT >= 33) {
            ClipDescription.EXTRA_IS_SENSITIVE
        } else {
            // Back-compat key used by the platform when compiling with lower SDKs
            "android.content.extra.IS_SENSITIVE"
        }

        clipData.description.extras = (clipData.description.extras ?: PersistableBundle()).apply {
            putBoolean(extrasKey, true)
        }

        clipboard.setClipEntry(ClipEntry(clipData))
    }

    /**
     * Best-effort: read plain text from clipboard.
     *
     * Returns null if clipboard is empty or first item doesn't contain text.
     *
     * IMPORTANT:
     * Do not rely on Android Context via nativeClipboard here. We can still read plain text
     * for common clipboard entries by using ClipData.Item.text directly.
     */
    suspend fun getText(clipboard: Clipboard): String? {
        val entry = clipboard.getClipEntry() ?: return null
        val clip = entry.clipData ?: return null
        if (clip.itemCount <= 0) return null

        val item = clip.getItemAt(0)

        // Prefer plain text entries (our primary use case: address, txid, monero URI).
        val directText = item.text?.toString()
        if (!directText.isNullOrEmpty()) return directText

        // If it's not a plain text clip (URI/intents), we intentionally don't try to resolve it here.
        return null
    }

    /**
     * Convenience wrapper to match old call sites that had a synchronous feel:
     * Provide the current Clipboard and run the block with it.
     */
    @Composable
    fun withClipboard(block: suspend (Clipboard) -> Unit): suspend () -> Unit {
        val clipboard = current()
        return { block(clipboard) }
    }

    /**
     * Convert a String into an AnnotatedString (useful for UI that wants annotated text).
     * Not required for clipboard operations, but kept for parity with older API expectations.
     */
    fun asAnnotated(text: String): AnnotatedString = AnnotatedString(text)
}
