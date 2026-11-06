package de.tomkainz.sonoslive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingInputTest {

    @Test fun plainCode() = assertEquals(PairingInput("123456", null), PairingInput.parse("123456"))

    @Test fun codeWithSpace() = assertEquals(PairingInput("123456", null), PairingInput.parse("123 456"))

    @Test fun codeWithSurroundingSpaces() = assertEquals(PairingInput("123456", null), PairingInput.parse("  123456\n"))

    @Test fun codeThenPort() = assertEquals(PairingInput("123456", 37123), PairingInput.parse("123456 37123"))

    @Test fun portThenCode() = assertEquals(PairingInput("123456", 37123), PairingInput.parse("37123 123456"))

    @Test fun wholeAddressFromDialog() =
        assertEquals(PairingInput("123456", 37123), PairingInput.parse("192.168.1.5:37123 123456"))

    @Test fun portOutOfRangeIsIgnored() = assertEquals(PairingInput("123456", null), PairingInput.parse("123456 70000"))

    @Test fun fiveDigitsAreNoCode() = assertNull(PairingInput.parse("12345"))

    @Test fun sevenDigitsAreNoCode() = assertNull(PairingInput.parse("1234567"))

    @Test fun noDigits() = assertNull(PairingInput.parse("abc"))

    @Test fun empty() = assertNull(PairingInput.parse(""))
}
