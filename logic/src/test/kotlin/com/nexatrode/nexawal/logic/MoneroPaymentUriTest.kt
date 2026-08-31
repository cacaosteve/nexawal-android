package com.nexatrode.nexawal.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneroPaymentUriTest {
    private val primary =
        "4B33mFPMq6mKi7Eiyd5XuyKRVMGVZz1Rqb9ZTyGApXW5d1aT7UBDZ89ewmnWFkzJ5wPd2SFbn313vCT8a4E2Qf4KQH4pNey"

    @Test
    fun addressExtracted() {
        val parsed = MoneroPaymentUri.parse("monero:$primary")
        assertEquals(primary, parsed?.address)
        assertNull(parsed?.amountXmr)
    }

    @Test
    fun amountExtracted() {
        val parsed = MoneroPaymentUri.parse("monero:$primary?tx_amount=1.5")
        assertEquals(primary, parsed?.address)
        assertEquals("1.5", parsed?.amountXmr)
    }

    @Test
    fun mixedCaseSchemeAndMetadataAreSupported() {
        val parsed = MoneroPaymentUri.parse(
            "MonErO://$primary?TX_AMOUNT=1.5&recipient_name=Coffee+Shop&message=two%20drinks%20%26%20tip"
        )
        assertEquals(primary, parsed?.address)
        assertEquals("1.5", parsed?.amountXmr)
        assertEquals("Coffee Shop", parsed?.recipientName)
        assertEquals("two drinks & tip", parsed?.description)
    }

    @Test
    fun plusAmountIsRejected() {
        assertNull(MoneroPaymentUri.parse("monero:$primary?tx_amount=%2B1.5"))
        assertNull(MoneroPaymentUri.parse("monero:$primary?tx_amount=+1.5"))
    }

    @Test
    fun conflictingAmountsAreRejected() {
        assertNull(MoneroPaymentUri.parse("monero:$primary?amount=1.5&tx_amount=2.0"))
    }

    @Test
    fun identicalAmountDuplicatesAreAccepted() {
        val parsed = MoneroPaymentUri.parse("monero:$primary?amount=1.5&tx_amount=1.5")
        assertEquals("1.5", parsed?.amountXmr)
    }

    @Test
    fun equivalentAmountAliasesNormalize() {
        val parsed = MoneroPaymentUri.parse("monero:$primary?amount=1.5&tx_amount=1.500")
        assertEquals("1.5", parsed?.amountXmr)
        assertEquals(
            MoneroPaymentUri.parseAmountPiconero("1.5"),
            MoneroPaymentUri.parseAmountPiconero("1.500"),
        )
    }

    @Test
    fun moreThanTwelveFractionalDigitsRejected() {
        assertNull(MoneroPaymentUri.parseAmountPiconero("1.1234567890123"))
        assertNull(MoneroPaymentUri.parse("monero:$primary?tx_amount=1.1234567890123"))
    }

    @Test
    fun spendAndViewKeysIgnoredAsSendTargets() {
        val uri = "monero:$primary?spend_key=deadbeefdeadbeef&view_key=cafebabecafebabe&tx_amount=1.0"
        val parsed = MoneroPaymentUri.parse(uri)
        assertEquals(primary, parsed?.address)
        assertEquals("1.0", parsed?.amountXmr)
        assertNotEquals("deadbeefdeadbeef", parsed?.address)
        assertNotEquals("cafebabecafebabe", parsed?.address)
    }

    @Test
    fun slashSlashPrefix() {
        val parsed = MoneroPaymentUri.parse("monero://$primary?amount=0.25")
        assertEquals(primary, parsed?.address)
        assertEquals("0.25", parsed?.amountXmr)
    }

    @Test
    fun nonMoneroRejected() {
        assertNull(MoneroPaymentUri.parse(primary))
        assertNull(MoneroPaymentUri.parse("bitcoin:$primary"))
    }

    @Test
    fun buildUsesStrictQueryEncoding() {
        val uri = MoneroPaymentUri.build(
            address = primary,
            amountXmr = "1.25",
            description = "coffee & cake = good",
        )
        assertEquals(
            "monero:$primary?tx_amount=1.25&tx_description=coffee%20%26%20cake%20%3D%20good",
            uri,
        )
    }

    @Test
    fun completeAddressShape() {
        assertEquals(true, MoneroPaymentUri.hasCompleteAddressShape(primary))
        assertEquals(false, MoneroPaymentUri.hasCompleteAddressShape("4abc"))
    }
}
