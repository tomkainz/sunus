package de.tomkainz.sonoslive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnoseCodeTest {

    @Test fun findsCodeInMessage() =
        assertEquals(Diagnose.Code.D4, Diagnose.Code.fromText("No stream (error SL-D4)"))

    @Test fun noCodeInMessage() = assertNull(Diagnose.Code.fromText("Paired."))

    @Test fun unknownCode() = assertNull(Diagnose.Code.fromText("(error SL-Z9)"))

    @Test fun everyLabelIsFoundAgain() {
        Diagnose.Code.entries.forEach { assertEquals(it, Diagnose.Code.fromText("x (${it.label})")) }
    }

    @Test fun refusedConnectionMeansSpeakerUnreachable() = assertEquals(
        Diagnose.Code.D5, Diagnose.Code.forSpeakerFailure(java.net.ConnectException("refused"), 0)
    )

    @Test fun noRouteMeansSpeakerUnreachable() = assertEquals(
        Diagnose.Code.D5, Diagnose.Code.forSpeakerFailure(java.net.NoRouteToHostException("no route"), 0)
    )

    @Test fun answerWithoutStreamRequest() = assertEquals(
        Diagnose.Code.D4, Diagnose.Code.forSpeakerFailure(java.io.IOException("500"), 0)
    )

    @Test fun answerAfterStreamRequest() = assertEquals(
        Diagnose.Code.D3, Diagnose.Code.forSpeakerFailure(java.net.SocketTimeoutException("slow"), 2)
    )

    @Test fun everyCodeHasHelp() {
        Diagnose.Code.entries.forEach { assertTrue(it.name, it.help != 0) }
    }
}
