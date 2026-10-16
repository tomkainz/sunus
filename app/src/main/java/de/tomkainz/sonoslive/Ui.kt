package de.tomkainz.sonoslive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val ROW = RoundedCornerShape(20.dp)

@Composable
fun SonosLiveApp(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDiscover: () -> Unit,
    onSelect: (Speaker) -> Unit,
    onAddManual: (String) -> Unit,
    onSetVolume: (Int) -> Unit,
    onStartPairing: (Int?) -> Unit
) {
    val speakers by AppState.speakers.collectAsStateWithLifecycle()
    val selected by AppState.selected.collectAsStateWithLifecycle()
    val searching by AppState.searching.collectAsStateWithLifecycle()
    val mode by AppState.mode.collectAsStateWithLifecycle()
    val running by AppState.running.collectAsStateWithLifecycle()
    val status by AppState.status.collectAsStateWithLifecycle()
    val message by AppState.message.collectAsStateWithLifecycle()
    val audioFlowing by AppState.audioFlowing.collectAsStateWithLifecycle()
    val level by AppState.level.collectAsStateWithLifecycle()
    val volume by AppState.volume.collectAsStateWithLifecycle()
    val requestPairing by AppState.requestPairing.collectAsStateWithLifecycle()

    val c = MaterialTheme.colorScheme
    var showMore by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 200.dp)
        ) {
            Text(
                "Sonos Live",
                style = MaterialTheme.typography.headlineMedium,
                color = c.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 36.dp)
            )

            if (speakers.isEmpty()) {
                EmptySpeaker(searching, onDiscover)
            } else {
                speakers.forEachIndexed { i, sp ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    SpeakerRow(sp, sp.udn == selected?.udn) { onSelect(sp) }
                }
            }

            Spacer(Modifier.height(32.dp))
            ModeToggle(mode, enabled = !running)
            Text(
                if (mode == CaptureMode.ALL_APPS) "Jede App, auch gesperrte · Handy wird stumm"
                else "Ohne Einrichtung · manche Apps sperren die Aufnahme",
                style = MaterialTheme.typography.labelSmall,
                color = c.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            )

            if (selected != null && volume != null) {
                Spacer(Modifier.height(36.dp))
                VolumeLine(volume!!, onSetVolume)
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            Text(
                statusText(running, status, audioFlowing, selected, searching),
                style = MaterialTheme.typography.labelSmall,
                color = if (running) c.primary else c.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconSpot(onClick = onDiscover) {
                    if (searching) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.onSurfaceVariant)
                    else SearchGlyph()
                }
                PowerButton(running, selected != null, audioFlowing, level, onStart, onStop)
                IconSpot(onClick = { showMore = true }) { MoreGlyph() }
            }
        }

        message?.let { Snackbar(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp)) }

        if (showMore) MoreSheet(mode, running, onAddManual) { showMore = false }
        if (requestPairing) PairingDialog(onStartPairing)
    }
}

private fun statusText(
    running: Boolean, status: String, audioFlowing: Boolean, selected: Speaker?, searching: Boolean
): String = when {
    running && status.startsWith("Live") ->
        "Live · ${selected?.room ?: ""}" + if (audioFlowing) "" else " · still"
    running -> status
    searching -> "Suche Lautsprecher..."
    selected == null -> "Kein Lautsprecher gewählt"
    else -> "Bereit"
}

// ---- Lautsprecher -------------------------------------------------------

@Composable
private fun SpeakerRow(sp: Speaker, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = ROW,
        color = if (selected) c.primaryContainer.copy(alpha = 0.55f) else c.surfaceContainer
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SpeakerGlyph(selected)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(sp.room, style = MaterialTheme.typography.titleMedium, color = c.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sp.model.removePrefix("Sonos "), style = MaterialTheme.typography.labelSmall,
                    color = c.onSurfaceVariant)
            }
            if (selected) CheckGlyph()
        }
    }
}

@Composable
private fun EmptySpeaker(searching: Boolean, onDiscover: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().clickable(onClick = onDiscover), shape = ROW, color = c.surfaceContainer) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (searching) "Suche im WLAN..." else "Kein Sonos gefunden",
                style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            if (!searching) {
                Text("Tippen zum Suchen · IP unter ⋯", style = MaterialTheme.typography.labelSmall,
                    color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// ---- Betriebsart als schlanker Umschalter -------------------------------

@Composable
private fun ModeToggle(mode: CaptureMode, enabled: Boolean) {
    val c = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = CircleShape, color = c.surfaceContainer) {
        Row(Modifier.padding(4.dp)) {
            Pill("Alle Apps", mode == CaptureMode.ALL_APPS, enabled, Modifier.weight(1f)) {
                AppState.setMode(CaptureMode.ALL_APPS)
            }
            Pill("Standard", mode == CaptureMode.STANDARD, enabled, Modifier.weight(1f)) {
                AppState.setMode(CaptureMode.STANDARD)
            }
        }
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier
            .height(44.dp)
            .background(if (selected) c.primary else Color.Transparent, CircleShape)
            .clickable(enabled = enabled && !selected, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall,
            color = when {
                selected -> c.onPrimary
                enabled -> c.onSurfaceVariant
                else -> c.onSurfaceVariant.copy(alpha = 0.5f)
            })
    }
}

// ---- Lautstaerke --------------------------------------------------------

@Composable
private fun VolumeLine(volume: Int, onSetVolume: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        VolumeGlyph()
        Spacer(Modifier.width(12.dp))
        Slider(
            value = volume.toFloat(), onValueChange = { onSetVolume(it.toInt()) }, valueRange = 0f..100f,
            colors = SliderDefaults.colors(thumbColor = c.primary, activeTrackColor = c.primary),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Text("$volume", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant,
            fontFamily = FontFamily.Monospace, modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
    }
}

// ---- Power-Taste mit Pegel-Ring ----------------------------------------

@Composable
private fun PowerButton(
    running: Boolean, canStart: Boolean, audioFlowing: Boolean, level: Float,
    onStart: () -> Unit, onStop: () -> Unit
) {
    val c = MaterialTheme.colorScheme
    val lvl by animateFloatAsState(if (running && audioFlowing) level.coerceIn(0f, 1f) else 0f,
        tween(140), label = "ring")
    val active = canStart || running
    val fill = when {
        running -> c.primary
        canStart -> c.primaryContainer
        else -> c.surfaceContainer
    }
    val fg = when {
        running -> c.onPrimary
        canStart -> c.onPrimaryContainer
        else -> c.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
        // Leuchtring: waechst mit dem Pegel, nur wenn wirklich Ton fliesst.
        Canvas(Modifier.fillMaxSize()) {
            if (running) {
                val base = 34.dp.toPx()
                drawCircle(c.primary.copy(alpha = 0.16f), radius = base + 6.dp.toPx() + lvl * 12.dp.toPx())
                drawCircle(c.primary.copy(alpha = 0.10f), radius = base + 14.dp.toPx() + lvl * 18.dp.toPx())
            }
        }
        Box(
            Modifier.size(68.dp).background(fill, CircleShape)
                .clickable(enabled = active) { if (running) onStop() else onStart() },
            contentAlignment = Alignment.Center
        ) {
            if (running) StopGlyph(fg) else PowerGlyph(fg)
        }
    }
}

@Composable
private fun IconSpot(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Snackbar(text: String, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Surface(modifier.padding(horizontal = 24.dp), shape = RoundedCornerShape(14.dp), color = c.surfaceContainerHigh) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.onSurface, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
    }
}

// ---- "Mehr": IP von Hand, Handy stumm, Hinweise ------------------------

@Composable
private fun MoreSheet(mode: CaptureMode, running: Boolean, onAddManual: (String) -> Unit, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val muteLocal by AppState.muteLocal.collectAsStateWithLifecycle()
    var ip by remember { mutableStateOf("") }

    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            Modifier.fillMaxWidth().clickable(enabled = false) {},
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = c.surfaceContainerHigh
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).background(c.outline, CircleShape))
                }

                Text("Lautsprecher per IP", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = ip, onValueChange = { ip = it }, placeholder = { Text("192.168.x.x") },
                        singleLine = true, shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onAddManual(ip); ip = ""; onClose() }) { Text("OK") }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Handy stumm", style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                        Text("Nur Standard-Modus (Alle Apps ist immer stumm)",
                            style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                    }
                    Switch(
                        checked = muteLocal, enabled = !running,
                        onCheckedChange = { AppState.setMuteLocal(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = c.primary, checkedThumbColor = c.onPrimary)
                    )
                }

                HorizontalDivider(color = c.outlineVariant)

                Hint("In der Sonos-App unter Konto > Datenschutz und Sicherheit > Verbindungssicherheit " +
                    "muss UPnP an sein (Standard).")
                Hint("Verlustfreie Übertragung (PCM). Der Ton kommt etwas später am Sonos an: " +
                    "für Musik ideal, für Videos nicht lippensynchron.")
                if (mode == CaptureMode.ALL_APPS) {
                    Hint("Alle Apps: einmal \"Kabelloses Debugging\" in den Entwickleroptionen einschalten " +
                        "und koppeln. Kein Root nötig.")
                }

                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Schließen") }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---- Kopplung (Alle-Apps-Modus) -----------------------------------------

@Composable
private fun PairingDialog(onStartPairing: (Int?) -> Unit) {
    val c = MaterialTheme.colorScheme
    val busy by AppState.adbBusy.collectAsStateWithLifecycle()
    val adbMessage by AppState.adbMessage.collectAsStateWithLifecycle()
    var manual by remember { mutableStateOf(false) }
    var port by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).padding(20.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = c.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Alle Apps einrichten", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Text("1. Auf „Kopplung starten“ tippen. Die Entwickleroptionen öffnen sich.\n" +
                    "2. „Kabelloses Debugging“ einschalten, antippen und „Gerät mit Kopplungscode koppeln“ wählen.\n" +
                    "3. Den Dialog offen lassen, die Benachrichtigungsleiste herunterziehen und bei „Sonos Live koppeln“ " +
                    "den 6-stelligen Code eingeben.",
                    style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                Text("Den Port findet die App selbst. Sind die Entwickleroptionen noch versteckt: " +
                    "Einstellungen > Über das Telefon > 7× auf „Build-Nummer“ tippen.",
                    style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)

                if (manual) {
                    OutlinedTextField(
                        value = port, onValueChange = { port = it.filter { ch -> ch.isDigit() }.take(5) },
                        label = { Text("Port aus dem Kopplungsdialog") }, singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                adbMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.primary) }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { manual = !manual }) {
                        Text(if (manual) "Port automatisch" else "Port selbst eintragen",
                            style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { AppState.requestPairing.value = false }) { Text("Schließen") }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { onStartPairing(if (manual) port.toIntOrNull() else null) },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary)
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.onPrimary)
                        else Text("Kopplung starten")
                    }
                }
            }
        }
    }
}

// ---- Selbst gezeichnete Icons ------------------------------------------

@Composable
private fun CheckGlyph() {
    val c = MaterialTheme.colorScheme
    Canvas(Modifier.size(22.dp)) {
        val w = size.minDimension * 0.11f
        drawLine(c.primary, Offset(size.width * 0.18f, size.height * 0.52f),
            Offset(size.width * 0.42f, size.height * 0.74f), w, StrokeCap.Round)
        drawLine(c.primary, Offset(size.width * 0.42f, size.height * 0.74f),
            Offset(size.width * 0.82f, size.height * 0.28f), w, StrokeCap.Round)
    }
}

@Composable
private fun SpeakerGlyph(selected: Boolean) {
    val c = MaterialTheme.colorScheme
    val col = if (selected) c.primary else c.onSurfaceVariant
    Canvas(Modifier.size(26.dp)) {
        val w = size.minDimension * 0.11f
        drawRoundRect(col, topLeft = Offset(size.width * 0.2f, size.height * 0.06f),
            size = Size(size.width * 0.6f, size.height * 0.88f),
            cornerRadius = CornerRadius(size.width * 0.16f), style = Stroke(w))
        drawCircle(col, radius = size.minDimension * 0.15f, center = Offset(center.x, size.height * 0.6f), style = Stroke(w))
        drawCircle(col, radius = size.minDimension * 0.035f, center = Offset(center.x, size.height * 0.24f))
    }
}

@Composable
private fun VolumeGlyph() {
    val c = MaterialTheme.colorScheme
    Canvas(Modifier.size(22.dp)) {
        val col = c.onSurfaceVariant
        val w = size.minDimension * 0.1f
        val body = Path().apply {
            moveTo(size.width * 0.12f, size.height * 0.38f)
            lineTo(size.width * 0.32f, size.height * 0.38f)
            lineTo(size.width * 0.54f, size.height * 0.18f)
            lineTo(size.width * 0.54f, size.height * 0.82f)
            lineTo(size.width * 0.32f, size.height * 0.62f)
            lineTo(size.width * 0.12f, size.height * 0.62f)
            close()
        }
        drawPath(body, col)
        val r = size.minDimension * 0.24f
        drawArc(col, startAngle = -45f, sweepAngle = 90f, useCenter = false,
            topLeft = Offset(size.width * 0.5f - r, center.y - r), size = Size(r * 2, r * 2),
            style = Stroke(w, cap = StrokeCap.Round))
    }
}

@Composable
private fun PowerGlyph(color: Color) {
    Canvas(Modifier.size(30.dp)) {
        val w = size.minDimension * 0.11f
        val r = size.minDimension * 0.33f
        drawArc(color, startAngle = -62f, sweepAngle = 304f, useCenter = false,
            topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2),
            style = Stroke(width = w, cap = StrokeCap.Round))
        drawLine(color, Offset(center.x, center.y - r - w * 0.3f), Offset(center.x, center.y - r * 0.15f),
            w, StrokeCap.Round)
    }
}

@Composable
private fun StopGlyph(color: Color) {
    Canvas(Modifier.size(26.dp)) {
        val s = size.minDimension * 0.56f
        drawRoundRect(color, topLeft = Offset(center.x - s / 2, center.y - s / 2),
            size = Size(s, s), cornerRadius = CornerRadius(s * 0.22f, s * 0.22f))
    }
}

/** Lupe (Suchen). */
@Composable
private fun SearchGlyph() {
    val c = MaterialTheme.colorScheme
    Canvas(Modifier.size(24.dp)) {
        val col = c.onSurfaceVariant
        val w = size.minDimension * 0.11f
        val r = size.minDimension * 0.27f
        val lens = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(col, radius = r, center = lens, style = Stroke(width = w))
        drawLine(col, Offset(lens.x + r * 0.72f, lens.y + r * 0.72f),
            Offset(size.width * 0.84f, size.height * 0.84f), w, StrokeCap.Round)
    }
}

/** Drei Punkte (Mehr). */
@Composable
private fun MoreGlyph() {
    val c = MaterialTheme.colorScheme
    Canvas(Modifier.size(24.dp)) {
        val r = size.minDimension * 0.08f
        listOf(0.22f, 0.5f, 0.78f).forEach { x ->
            drawCircle(c.onSurfaceVariant, radius = r, center = Offset(size.width * x, center.y))
        }
    }
}
