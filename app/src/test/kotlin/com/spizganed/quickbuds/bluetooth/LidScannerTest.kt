package com.spizganed.quickbuds.bluetooth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lid advert of OPPO Enco Buds2 (12:34:56:78:9A:BC), captured with the case opening and the phone connecting.
 * Manufacturer data after the company id 0x079A.
 */
class LidScannerTest {

    private val addr = "12:34:56:78:9A:BC"
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Right after the lid opens: both buds, not connected to a phone. */
    private val open = hex("1048063f00019805bc9a7856341200a1a1b502")
    /** The first packets, each bud alone. */
    private val openOneBud = hex("1048063f00019805bc9a785634120000a1b502")
    /** A second later: connected to the phone (byte 14 = 01). */
    private val connected = hex("1048063f00019804bc9a7856341201a1a1b502")

    @Test fun theAddressIsReversedInTheAdvert() {
        assertArrayEquals(hex("bc9a78563412"), LidScanner.addressBytes(addr))
    }

    @Test fun aMalformedAddressGivesNull() {
        assertNull(LidScanner.addressBytes("12:34:56"))
        assertNull(LidScanner.addressBytes("zz:34:56:78:9A:BC"))
    }

    @Test fun theOpenAdvertOfOurBudsMatches() {
        assertTrue(LidScanner.isFreeAdvert(open, addr))
        assertTrue(LidScanner.isFreeAdvert(openOneBud, addr))
    }

    @Test fun anAdvertWhileConnectedToAPhoneDoesNotMatch() {
        assertFalse(LidScanner.isFreeAdvert(connected, addr))
    }

    @Test fun otherBudsDoNotMatch() {
        assertFalse(LidScanner.isFreeAdvert(open, "12:34:56:78:9A:BD"))
    }

    @Test fun aShortAdvertDoesNotMatch() {
        assertFalse(LidScanner.isFreeAdvert(open.copyOf(14), addr))
    }
}
