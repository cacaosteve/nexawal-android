package com.nexatrode.nexawal

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexatrode.nexawal.walletcore.WalletCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.UnsupportedOperationException

@RunWith(AndroidJUnit4::class)
class WalletCoreSmokeTest {

    @Test
    fun smoke() {
        val args = InstrumentationRegistry.getArguments()

        val mnemonic = args.string("wallet_mnemonic")
            ?: throw IllegalArgumentException("Missing instrumentation arg: wallet_mnemonic")
        val walletId = args.string("wallet_id") ?: "android_smoke_wallet"
        val restoreHeight = args.long("wallet_restore_height", 0L)
        val nodeUrl = args.string("monero_url")
        val destinationAddress = args.string("smoke_dest_address")
        val runRefresh = args.bool("smoke_refresh", false)
        val runCancel = args.bool("smoke_cancel_refresh", false)
        val refreshWaitMs = args.long("smoke_refresh_wait_ms", 30_000L)
        val mainnet = !args.bool("stagenet", false)

        try {
            val generatedMnemonic = WalletCore.generateMnemonicEnglish()
            val generatedWordCount = generatedMnemonic.trim().split(Regex("\\s+")).size
            assertEquals(25, generatedWordCount)
            println("SMOKE generated mnemonic words: $generatedWordCount")
        } catch (_: UnsupportedOperationException) {
            println("SMOKE generated mnemonic: skipped (not exported by Android wallet core build)")
        }

        val primary = WalletCore.derivePrimaryAddressFromMnemonic(mnemonic, mainnet = mainnet)
        assertTrue(primary.isNotBlank())

        val subaddress = WalletCore.deriveSubaddressFromMnemonic(
            mnemonic = mnemonic,
            accountIndex = 0,
            subaddressIndex = 1,
            mainnet = mainnet
        )
        assertTrue(subaddress.isNotBlank())

        WalletCore.openFromMnemonic(
            walletId = walletId,
            mnemonic = mnemonic,
            restoreHeight = restoreHeight,
            mainnet = mainnet
        )

        val initialStatus = WalletCore.syncStatus(walletId)
        println("SMOKE syncStatus initial: $initialStatus")

        val exportedCache = WalletCore.exportCache(walletId)
        if (exportedCache != null) {
            WalletCore.importCache(walletId, exportedCache)
            println("SMOKE cache round-trip: ${exportedCache.size} bytes")
        } else {
            println("SMOKE cache round-trip: skipped (no cache yet)")
        }

        if (runRefresh) {
            if (runCancel) {
                WalletCore.refreshAsync(walletId, nodeUrl)
                WalletCore.refreshCancel(walletId)
                println("SMOKE refresh cancel requested")
            } else {
                WalletCore.refreshAsync(walletId, nodeUrl)
                val deadline = System.currentTimeMillis() + refreshWaitMs
                var progressedStatus = initialStatus
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(1_000L)
                    progressedStatus = WalletCore.syncStatus(walletId)
                    if (progressedStatus.lastScanned > initialStatus.lastScanned ||
                        progressedStatus.chainHeight > initialStatus.chainHeight) {
                        break
                    }
                }
                WalletCore.refreshCancel(walletId)
                println("SMOKE refresh progress: initial=$initialStatus current=$progressedStatus")
                assertTrue(
                    "expected refresh to advance lastScanned or chainHeight within ${refreshWaitMs}ms",
                    progressedStatus.lastScanned > initialStatus.lastScanned ||
                        progressedStatus.chainHeight > initialStatus.chainHeight
                )
            }
        } else {
            println("SMOKE refresh skipped")
        }

        val balance = WalletCore.getBalance(walletId)
        println("SMOKE balance: $balance")

        val filteredBalance = WalletCore.getBalanceWithFilter(walletId, """{"subaddress_minor":0}""")
        println("SMOKE filtered balance: $filteredBalance")

        val transfersJson = WalletCore.listTransfersJson(walletId)
        assertTrue(transfersJson.isNotBlank())
        println("SMOKE transfers json length: ${transfersJson.length}")

        if (!destinationAddress.isNullOrBlank()) {
            val previewFee = WalletCore.previewFeeJson(
                walletId = walletId,
                destinationsJson = """[{"address":"$destinationAddress","amount":1}]""",
                nodeUrl = nodeUrl
            )
            assertTrue(previewFee.isNotBlank())
            println("SMOKE preview fee: $previewFee")

            val previewFeeFiltered = WalletCore.previewFeeJsonWithFilter(
                walletId = walletId,
                destinationsJson = """[{"address":"$destinationAddress","amount":1}]""",
                filterJson = """{"subaddress_minor":0}""",
                nodeUrl = nodeUrl
            )
            assertTrue(previewFeeFiltered.isNotBlank())
            println("SMOKE preview fee filtered: $previewFeeFiltered")

            val previewSweep = WalletCore.previewSweepJson(
                walletId = walletId,
                toAddress = destinationAddress,
                nodeUrl = nodeUrl
            )
            assertTrue(previewSweep.isNotBlank())
            println("SMOKE preview sweep: $previewSweep")

            val previewSweepFiltered = WalletCore.previewSweepJsonWithFilter(
                walletId = walletId,
                toAddress = destinationAddress,
                filterJson = """{"subaddress_minor":0}""",
                nodeUrl = nodeUrl
            )
            assertTrue(previewSweepFiltered.isNotBlank())
            println("SMOKE preview sweep filtered: $previewSweepFiltered")
        } else {
            println("SMOKE send/sweep previews skipped")
        }
    }

    private fun Bundle.string(key: String): String? =
        getString(key)?.trim()?.takeIf { it.isNotEmpty() }

    private fun Bundle.long(key: String, defaultValue: Long): Long =
        string(key)?.toLongOrNull() ?: defaultValue

    private fun Bundle.bool(key: String, defaultValue: Boolean): Boolean {
        val raw = string(key)?.lowercase() ?: return defaultValue
        return when (raw) {
            "1", "true", "yes", "y", "on" -> true
            "0", "false", "no", "n", "off" -> false
            else -> defaultValue
        }
    }
}
