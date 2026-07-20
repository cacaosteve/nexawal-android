package com.nexatrode.nexawal.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRoutingTest {

    private val clearnet = "http://127.0.0.1:18092"
    private val i2p = "cvxtgqjorfif6i5x5fenys6fj7hzddbgavpyutps6gphywnlklqa.b32.i2p:18081"

    @Test
    fun scanUsesClearnetForClearnetAndHybrid() {
        assertEquals(clearnet, NetworkRouting.scanNodeUrl(NetworkRouting.Policy.CLEARNET, clearnet, i2p))
        assertEquals(clearnet, NetworkRouting.scanNodeUrl(NetworkRouting.Policy.HYBRID, clearnet, i2p))
    }

    @Test
    fun scanUsesI2pOnlyForI2pPolicy() {
        assertEquals(
            NetworkRouting.normalizeUrl(i2p),
            NetworkRouting.scanNodeUrl(NetworkRouting.Policy.I2P, clearnet, i2p),
        )
    }

    @Test
    fun broadcastUsesI2pForI2pAndHybrid() {
        val expected = NetworkRouting.normalizeUrl(i2p)
        assertEquals(expected, NetworkRouting.broadcastNodeUrl(NetworkRouting.Policy.I2P, clearnet, i2p))
        assertEquals(expected, NetworkRouting.broadcastNodeUrl(NetworkRouting.Policy.HYBRID, clearnet, i2p))
        assertEquals(clearnet, NetworkRouting.broadcastNodeUrl(NetworkRouting.Policy.CLEARNET, clearnet, i2p))
    }

    @Test
    fun proxyRequiredAndPolicyAware() {
        assertFalse(NetworkRouting.shouldUseI2pHttpProxy(NetworkRouting.Policy.I2P, proxyConfigured = false, forBroadcast = true))
        assertFalse(NetworkRouting.shouldUseI2pHttpProxy(NetworkRouting.Policy.CLEARNET, proxyConfigured = true, forBroadcast = true))
        assertTrue(NetworkRouting.shouldUseI2pHttpProxy(NetworkRouting.Policy.I2P, proxyConfigured = true, forBroadcast = false))
        assertTrue(NetworkRouting.shouldUseI2pHttpProxy(NetworkRouting.Policy.HYBRID, proxyConfigured = true, forBroadcast = true))
        assertFalse(NetworkRouting.shouldUseI2pHttpProxy(NetworkRouting.Policy.HYBRID, proxyConfigured = true, forBroadcast = false))
    }

    @Test
    fun normalizeUrlAddsScheme() {
        assertEquals("http://host:18081", NetworkRouting.normalizeUrl("host:18081"))
        assertEquals("https://host:443", NetworkRouting.normalizeUrl("host:443"))
        assertEquals("https://host:18081", NetworkRouting.normalizeUrl("https://host:18081"))
    }
}
