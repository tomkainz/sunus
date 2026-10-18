package de.tomkainz.sonoslive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Licenses of the app and all bundled libraries. The texts are stored
 * unchanged in assets/licenses; NOTICES.txt lists authors and versions.
 */
private val LICENSE_FILES = listOf(
    "Apache License 2.0" to "Apache-2.0.txt",
    "GNU GPL v2.0 + Classpath exception" to "GPL-2.0.txt",
    "GNU GPL v3.0" to "GPL-3.0.txt",
    "GNU LGPL v3.0" to "LGPL-3.0.txt",
    "GNU LGPL v2.1" to "LGPL-2.1.txt",
)

@Composable
fun LicensesScreen(onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    var open by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Back closes the open text first, then the page.
    BackHandler { if (open != null) open = null else onClose() }

    Surface(Modifier.fillMaxSize(), color = c.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val shown = open
            if (shown == null) {
                Text(stringResource(R.string.licenses), style = MaterialTheme.typography.headlineMedium,
                    color = c.onBackground, modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.licenses_intro), style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant)
                val notices = stringResource(R.string.licenses_notices)
                val app = stringResource(R.string.licenses_app)
                (listOf(notices to "NOTICES.txt", app to "LICENSE.txt") + LICENSE_FILES).forEach { entry ->
                    Surface(
                        Modifier.fillMaxWidth().clickable { open = entry },
                        shape = RoundedCornerShape(20.dp), color = c.surfaceContainer
                    ) {
                        Text(entry.first, style = MaterialTheme.typography.titleMedium, color = c.onSurface,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp))
                    }
                }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.close))
                }
            } else {
                Text(shown.first, style = MaterialTheme.typography.titleMedium, color = c.onBackground,
                    modifier = Modifier.padding(top = 8.dp))
                val text = remember(shown) {
                    runCatching {
                        context.assets.open("licenses/${shown.second}").bufferedReader().use { reflow(it.readText()) }
                    }.getOrDefault("")
                }
                Text(text, style = MaterialTheme.typography.bodySmall, color = c.onSurface,
                    modifier = Modifier.fillMaxWidth().background(c.surfaceContainer, RoundedCornerShape(14.dp))
                        .padding(12.dp))
                TextButton(onClick = { open = null }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

/** List items, section numbers and separator lines start a new line. */
private val NEW_LINE = Regex("""^(-|\*|\d+\.|\(?[a-z0-9]\)|-{4,}|[A-Z][A-Z &"-]{5,}$|Copyright)""")

/**
 * The license texts are hard-wrapped at 70 to 80 columns. On a phone every
 * line would wrap a second time, so the lines of a paragraph are joined
 * back together.
 */
private fun reflow(text: String): String {
    val out = StringBuilder()
    var inParagraph = false
    for (raw in text.lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> { if (inParagraph) out.append("\n\n"); inParagraph = false }
            inParagraph && !NEW_LINE.containsMatchIn(line) -> out.append(' ').append(line)
            else -> { if (inParagraph) out.append('\n'); out.append(line); inParagraph = true }
        }
    }
    return out.toString().trim()
}
