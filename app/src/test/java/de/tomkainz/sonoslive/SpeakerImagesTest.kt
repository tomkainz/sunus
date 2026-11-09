package de.tomkainz.sonoslive

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerImagesTest {

    @Test fun modelNamesBecomePhotoNames() {
        assertEquals("play-1", SpeakerImages.key("Sonos Play:1"))
        assertEquals("arc-ultra", SpeakerImages.key("Sonos Arc Ultra"))
        assertEquals("connect-amp", SpeakerImages.key("Sonos Connect:Amp"))
        assertEquals("sub-mini", SpeakerImages.key("Sonos Sub Mini"))
        assertEquals("symfonisk-bookshelf", SpeakerImages.key("SYMFONISK Bookshelf"))
    }

    @Test fun lookAlikesShareAPhoto() {
        assertEquals("arc", SpeakerImages.key("Sonos Arc SL"))
        assertEquals("era-100", SpeakerImages.key("Sonos Era 100 Pro"))
    }

    @Test fun noModelNoName() = assertEquals("", SpeakerImages.key("Sonos"))

    @Test fun nameCannotLeaveTheFolder() = assertEquals("etc-passwd", SpeakerImages.key("../../etc/passwd"))
}
