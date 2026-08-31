package com.nexatrode.nexawal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferHistoryJsonTest {
    private val row = """{"txid":"${"ab".repeat(32)}","direction":"in","amount":42,"fee":7,"height":3600000,"timestamp":1786000000,"confirmations":10,"is_pending":false,"subaddress_major":0,"subaddress_minor":1}"""

    @Test
    fun legacyArrayRemainsSupported() {
        val history = TransferHistoryJson.decode("[$row]", "main_wallet")

        assertEquals(0, history.schemaVersion)
        assertNull(history.walletId)
        assertEquals(7L, history.transfers.single().fee)
        assertEquals(1, history.transfers.single().subaddressMinor)
    }

    @Test
    fun versionOneAcceptsAdditiveFields() {
        val json = """{"schema_version":1,"wallet_id":"main_wallet","last_scanned_height":3600010,"chain_height":3600020,"chain_time":1787000000,"future_metadata":true,"transfers":[$row]}"""
        val history = TransferHistoryJson.decode(json, "main_wallet")

        assertEquals(1, history.schemaVersion)
        assertEquals(3_600_010L, history.lastScannedHeight)
        assertEquals(1, history.transfers.size)
    }

    @Test
    fun futureVersionIsRejected() {
        val json = """{"schema_version":2,"wallet_id":"main_wallet","last_scanned_height":0,"chain_height":0,"chain_time":0,"transfers":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            TransferHistoryJson.decode(json, "main_wallet")
        }
    }

    @Test
    fun wrongWalletUnknownDirectionAndNegativeValuesAreRejected() {
        val wrongWallet = """{"schema_version":1,"wallet_id":"other","last_scanned_height":0,"chain_height":0,"chain_time":0,"transfers":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            TransferHistoryJson.decode(wrongWallet, "main_wallet")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferHistoryJson.decode("[${row.replace("\"in\"", "\"sideways\"")}]", "main_wallet")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferHistoryJson.decode("[${row.replace("\"amount\":42", "\"amount\":-1")}]", "main_wallet")
        }
    }
}
