package de.tomkainz.sonoslive

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerKindTest {

    private fun check(kind: SpeakerKind, vararg models: String) =
        models.forEach { assertEquals(it, kind, SpeakerKind.of(it)) }

    @Test fun soundbars() = check(SpeakerKind.BAR, "Sonos Arc Ultra", "Sonos Arc", "Sonos Beam", "Sonos Ray", "Sonos Playbar", "Sonos Playbase")

    @Test fun subwoofers() = check(SpeakerKind.SUB, "Sonos Sub", "Sonos Sub Mini", "Sonos Sub 4")

    @Test fun boxes() = check(SpeakerKind.BOX, "Sonos Amp", "Sonos Port", "Sonos Connect", "Sonos Connect:Amp", "Sonos Boost")

    @Test fun everythingElseIsASpeaker() = check(SpeakerKind.TOWER, "Sonos Play:1", "Sonos One", "Sonos Era 100", "Sonos Move 2", "Sonos", "")

    @Test fun homePods() {
        check(SpeakerKind.HOMEPOD, "HomePod")
        check(SpeakerKind.HOMEPOD_MINI, "HomePod mini")
    }

    @Test fun partsOfWordsDoNotCount() = check(SpeakerKind.TOWER, "Sonos Subway", "Sonos Array")
}
