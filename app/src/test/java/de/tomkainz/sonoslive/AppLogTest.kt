package de.tomkainz.sonoslive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppLogTest {

    @get:Rule val folder = TemporaryFolder()

    @Test fun linesStayInOrderAndGetATime() {
        AppLog.init(folder.root)
        AppLog.append("first")
        AppLog.append("second")
        val lines = AppLog.read().trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].matches(Regex("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3}  first")))
        assertTrue(lines[1], lines[1].endsWith("second"))
    }

    @Test fun oldestLinesGoFirstButTheNewestSurvive() {
        AppLog.init(folder.root, maxBytes = 200)
        repeat(100) { AppLog.append("line $it") }
        val text = AppLog.read()
        assertTrue(text, text.trim().endsWith("line 99"))
        assertFalse(text, text.contains("line 0\n"))
        assertTrue(text.length < 600)
    }

    @Test fun nothingLoggedYetReadsEmpty() {
        AppLog.init(folder.root)
        assertEquals("", AppLog.read())
    }
}
