package de.tomkainz.sonoslive

import de.tomkainz.sonoslive.SessionPicker.Candidate
import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingPickTest {

    private val own = "de.tomkainz.sonoslive"

    @Test fun nothingToShow() = assertEquals(-1, SessionPicker.pick(emptyList(), own))

    @Test fun playingWinsOverFirst() = assertEquals(
        1, SessionPicker.pick(listOf(Candidate("a", "Paused", false), Candidate("b", "Playing", true)), own)
    )

    @Test fun firstWithTitleIfNothingPlays() = assertEquals(
        1, SessionPicker.pick(listOf(Candidate("a", null, false), Candidate("b", "Paused", false)), own)
    )

    @Test fun sessionWithoutTitleIsSkippedEvenIfPlaying() = assertEquals(
        1, SessionPicker.pick(listOf(Candidate("telecom", "", true), Candidate("b", "Paused", false)), own)
    )

    @Test fun ownSessionIsIgnored() = assertEquals(-1, SessionPicker.pick(listOf(Candidate(own, "Me", true)), own))
}
