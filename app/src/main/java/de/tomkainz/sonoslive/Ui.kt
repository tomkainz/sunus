package de.tomkainz.sonoslive

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

internal val ROW = RoundedCornerShape(20.dp)

@Composable
fun SonosLiveApp(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDiscover: () -> Unit,
    onSelect: (Speaker) -> Unit,
    onAddManual: (String) -> Unit,
    onSetVolume: (Int) -> Unit,
    onStartPairing: () -> Unit
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
    val muted by AppState.muted.collectAsStateWithLifecycle()
    val eq by AppState.eq.collectAsStateWithLifecycle()
    val requestPairing by AppState.requestPairing.collectAsStateWithLifecycle()
    val nowPlaying by NowPlaying.info.collectAsStateWithLifecycle()
    val nowPlayingOn by NowPlaying.enabled.collectAsStateWithLifecycle()
    val musicActive by NowPlaying.musicActive.collectAsStateWithLifecycle()
    val clients by AppState.clients.collectAsStateWithLifecycle()

    val c = MaterialTheme.colorScheme
    var showMore by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 200.dp)
        ) {
            // App icon as a tile: the dark background keeps the logo's light
            // line visible in the light theme too.
            Box(
                Modifier
                    .padding(top = 20.dp)
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF181818))
                    .align(Alignment.CenterHorizontally),
                contentAlignment = Alignment.Center
            ) {
                Image(painterResource(R.drawable.logo_mark), contentDescription = null, modifier = Modifier.width(48.dp))
            }
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = c.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 28.dp)
            )

            SectionLabel(stringResource(R.string.section_speaker))
            if (speakers.isEmpty()) {
                EmptySpeaker(searching, onDiscover)
            } else {
                speakers.forEachIndexed { i, sp ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    val isSelected = sp.udn == selected?.udn || sp.alternatives.any { it.udn == selected?.udn }
                    SpeakerRow(sp, isSelected, live = isSelected && running, audioFlowing, level) { onSelect(sp) }
                }
            }

            SectionDivider()
            SectionLabel(stringResource(R.string.section_source))
            ModeToggle(mode, enabled = !running)
            Text(
                stringResource(when {
                    !AppState.allAppsSupported -> R.string.mode_all_apps_unavailable
                    mode == CaptureMode.ALL_APPS -> R.string.mode_all_apps_hint
                    else -> R.string.mode_standard_hint
                }),
                style = MaterialTheme.typography.labelSmall,
                color = c.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            )

            SectionDivider()
            NowPlayingHeader(
                statusText(running, status, audioFlowing, clients, selected, searching),
                connected = running && status.startsWith("Live") && clients > 0
            )
            NowPlayingCard(nowPlaying, nowPlayingOn, musicActive)

            if (selected != null && volume != null) {
                SectionDivider()
                SectionLabel(stringResource(R.string.section_sound))
                VolumeCard(volume!!, muted, onSetVolume)
                eq?.let {
                    Spacer(Modifier.height(10.dp))
                    EqCard(it)
                }
            }
        }

        // Scrolled content fades out under the status bar and the controls.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(c.background)
        )
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(Brush.verticalGradient(listOf(c.background.copy(alpha = 0f), c.background)))
            )
            BottomBar(onDiscover, searching, { showMore = true }) {
                PowerButton(running, selected != null, audioFlowing, level, onStart, onStop, it)
            }
        }

        message?.let { Snackbar(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp)) }

        if (showMore) MoreSheet(mode, running, onAddManual, onLicenses = { showMore = false; showLicenses = true }) {
            showMore = false
        }
        if (requestPairing) PairingDialog(onStartPairing)
        if (showLicenses) LicensesScreen { showLicenses = false }
    }
}

@Composable
private fun statusText(
    running: Boolean, status: String, audioFlowing: Boolean, clients: Int, selected: Speaker?, searching: Boolean
): String = when {
    // The live statuses start with "Live" in every language. Connected means
    // the speaker really fetches the stream, not just that the app sent it there.
    running && status.startsWith("Live") -> stringResource(
        when {
            clients == 0 -> R.string.status_waiting_speaker
            audioFlowing -> R.string.status_connected
            else -> R.string.status_connected_silent
        }
    )
    running -> status
    searching -> stringResource(R.string.status_searching)
    selected == null -> stringResource(R.string.status_no_speaker)
    else -> stringResource(R.string.status_ready)
}

// ---- Speakers -----------------------------------------------------------

@Composable
private fun SpeakerRow(
    sp: Speaker, selected: Boolean, live: Boolean, audioFlowing: Boolean, level: Float, onClick: () -> Unit
) {
    val c = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = ROW,
        color = if (selected) c.surfaceContainer else c.surfaceContainerLow
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SpeakerPicture(sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(sp.room, style = MaterialTheme.typography.titleMedium, color = c.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    sp.model.removePrefix("Sonos ") +
                        listOfNotNull(
                            sp.protocol.label?.takeUnless { it in sp.model },
                            "Alpha".takeIf { sp.protocol.alpha }
                        ).joinToString("") { " · $it" },
                    style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            when {
                live -> LiveBars(audioFlowing, level)
                selected -> CheckGlyph()
            }
        }
    }
}

@Composable
private fun EmptySpeaker(searching: Boolean, onDiscover: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().clickable(onClick = onDiscover), shape = ROW, color = c.surfaceContainer) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(if (searching) R.string.empty_searching else R.string.empty_none),
                style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            if (!searching) {
                Text(stringResource(R.string.empty_hint), style = MaterialTheme.typography.labelSmall,
                    color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Small heading above a group of rows. */
@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 14.dp, bottom = 10.dp))
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** The heading of the player, with the state of the connection on the right. */
@Composable
private fun NowPlayingHeader(status: String, connected: Boolean) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.section_now_playing), style = MaterialTheme.typography.labelSmall,
            color = c.onBackground)
        Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
        if (connected) {
            VolumeGlyph(muted = false, color = c.primary)
            Spacer(Modifier.width(6.dp))
        }
        Text(status, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (connected) c.primary else c.onSurfaceVariant)
    }
}

// ---- Mode as a slim toggle ----------------------------------------------

@Composable
private fun ModeToggle(mode: CaptureMode, enabled: Boolean) {
    val c = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = CircleShape, color = c.surfaceContainer) {
        Row(Modifier.padding(4.dp)) {
            Pill(stringResource(R.string.mode_all_apps), mode == CaptureMode.ALL_APPS,
                enabled && AppState.allAppsSupported, Modifier.weight(1f)) {
                AppState.setMode(CaptureMode.ALL_APPS)
            }
            Pill(stringResource(R.string.mode_standard), mode == CaptureMode.STANDARD, enabled, Modifier.weight(1f)) {
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
            .then(if (selected) Modifier.shadow(5.dp, CircleShape, spotColor = c.primary) else Modifier)
            .background(if (selected) c.primary else Color.Transparent, CircleShape)
            .clip(CircleShape)
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

// ---- Volume -------------------------------------------------------------

/** Volume of the speaker; the speaker symbol mutes it. */
@Composable
private fun VolumeCard(volume: Int, muted: Boolean, onSetVolume: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    val accent = if (muted) c.onSurfaceVariant else c.primary
    val label = stringResource(if (muted) R.string.volume_unmute else R.string.volume_mute)
    Surface(Modifier.fillMaxWidth(), shape = ROW, color = c.surfaceContainer) {
        Row(
            Modifier.padding(start = 6.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconSpot(
                onClick = { AppState.setMuted(!muted) },
                modifier = Modifier.semantics { contentDescription = label }
            ) { VolumeGlyph(muted) }
            Spacer(Modifier.width(6.dp))
            Slider(
                value = volume.toFloat(), onValueChange = { onSetVolume(it.toInt()) }, valueRange = 0f..100f,
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text("$volume", style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR),
                color = c.onSurfaceVariant, modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
        }
    }
}

// ---- Bar and button ----------------------------------------------------

/** How far the bar rises around the button, and how far that hill reaches to each side. */
private val HILL_HEIGHT = 14.dp
private val HILL_REACH = 66.dp

/** The bar's top edge with a soft hill in the middle that carries the button. */
private class HillShape(private val height: Dp, private val reach: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val h = with(density) { height.toPx() }
        val r = with(density) { reach.toPx() }
        val mid = size.width / 2
        return Outline.Generic(Path().apply {
            moveTo(0f, h)
            lineTo(mid - r, h)
            cubicTo(mid - r * 0.58f, h, mid - r * 0.5f, 0f, mid, 0f)
            cubicTo(mid + r * 0.5f, 0f, mid + r * 0.58f, h, mid + r, h)
            lineTo(size.width, h)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        })
    }
}

/**
 * Fills [HillShape] with a soft shadow on top. The platform's elevation shadow falls
 * downwards, off the screen here. The strip beside the hill stays background, so scrolled
 * content does not show between the fade above the bar and the bar itself.
 */
private fun Modifier.barBackground(color: Color, background: Color, shadow: Color) = drawWithCache {
    val outline = HillShape(HILL_HEIGHT, HILL_REACH).createOutline(size, layoutDirection, this) as Outline.Generic
    val path = outline.path.asAndroidPath()
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        setShadowLayer(10.dp.toPx(), 0f, -1.dp.toPx(), shadow.toArgb())
    }
    onDrawBehind {
        drawRect(background, size = Size(size.width, HILL_HEIGHT.toPx()))
        drawIntoCanvas { it.nativeCanvas.drawPath(path, paint) }
    }
}

@Composable
private fun BottomBar(
    onDiscover: () -> Unit, searching: Boolean, onMore: () -> Unit, button: @Composable (Modifier) -> Unit
) {
    val c = MaterialTheme.colorScheme
    val searchLabel = stringResource(R.string.action_search)
    val moreLabel = stringResource(R.string.action_more)
    Box(
        Modifier
            .fillMaxWidth()
            .barBackground(c.surfaceContainerLow, c.background, Color.Black.copy(alpha = 0.14f))
            .pointerInput(Unit) {}
    ) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding().height(88.dp)) {
            IconSpot(
                onClick = onDiscover,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 28.dp, bottom = 16.dp)
                    .semantics { contentDescription = searchLabel }
            ) {
                if (searching) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.onSurfaceVariant)
                else SearchGlyph()
            }
            button(Modifier.align(Alignment.BottomCenter))
            IconSpot(
                onClick = onMore,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 16.dp)
                    .semantics { contentDescription = moreLabel }
            ) { MoreGlyph() }
        }
    }
}

/**
 * Glass button: a rim from light to dark around a lighter disc, as the only tinted
 * thing in the bar. While streaming it glows with the level. It gives way under the
 * finger and springs back.
 */
@Composable
private fun PowerButton(
    running: Boolean, canStart: Boolean, audioFlowing: Boolean, level: Float,
    onStart: () -> Unit, onStop: () -> Unit, modifier: Modifier
) {
    val c = MaterialTheme.colorScheme
    val active = canStart || running
    val glass = glassColors(active)
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "press")
    val lvl by animateFloatAsState(if (running && audioFlowing) level.coerceIn(0f, 1f) else 0f,
        tween(140), label = "ring")
    val label = stringResource(if (running) R.string.power_stop else R.string.power_start)
    Box(
        modifier
            .size(80.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                // Glow: grows with the level, and shows that the stream is on even in silence.
                if (running) {
                    val radius = size.minDimension / 2
                    drawCircle(c.primary.copy(alpha = 0.18f), radius + 4.dp.toPx() + lvl * 10.dp.toPx())
                    drawCircle(c.primary.copy(alpha = 0.10f), radius + 9.dp.toPx() + lvl * 16.dp.toPx())
                }
            }
            .shadow(if (active) 10.dp else 3.dp, CircleShape, ambientColor = glass.rimBottom, spotColor = glass.rimBottom)
            .clip(CircleShape)
            .drawBehind {
                val radius = size.minDimension / 2
                drawCircle(Brush.verticalGradient(listOf(glass.rimTop, glass.rimBottom)), radius)
                val disc = radius * 0.84f
                drawCircle(
                    Brush.verticalGradient(listOf(glass.discTop, glass.discBottom),
                        startY = center.y - disc, endY = center.y + disc),
                    disc
                )
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.34f), Color.Transparent), center, disc),
                    disc
                )
            }
            .clickable(interaction, ripple(), enabled = active, role = Role.Button) {
                haptic.performHapticFeedback(if (running) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                if (running) onStop() else onStart()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        if (running) StopGlyph(glass.icon) else PowerGlyph(glass.icon)
    }
}

@Composable
internal fun IconSpot(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

/**
 * Message; tapping dismisses it. It lies on top of the controls, so plain messages
 * hide by themselves. Errors with a code stay: they come with the report.
 */
@Composable
private fun Snackbar(text: String, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val withReport = Regex("SL-[A-Z][0-9]").containsMatchIn(text)
    LaunchedEffect(text) {
        if (withReport) return@LaunchedEffect
        delay((2500L + text.length * 50L).coerceAtMost(10_000L))
        AppState.message.value = null
    }
    Surface(
        modifier.padding(horizontal = 24.dp).clickable { AppState.message.value = null },
        shape = RoundedCornerShape(14.dp), color = c.surfaceContainerHigh, shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = c.onSurface, textAlign = TextAlign.Center)
            Diagnose.Code.fromText(text)?.let { code ->
                Text(stringResource(code.help), Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            if (withReport) {
                Row {
                    TextButton(onClick = { Diagnose.share(context) }) { Text(stringResource(R.string.report_share)) }
                    TextButton(onClick = { Diagnose.saveToDownloads(context) }) { Text(stringResource(R.string.report_save)) }
                }
            }
        }
    }
}

// ---- "More": log, manual IP, mute phone, hints, licenses ---------------

@Composable
private fun MoreSheet(
    mode: CaptureMode, running: Boolean, onAddManual: (String) -> Unit, onLicenses: () -> Unit, onClose: () -> Unit
) {
    val c = MaterialTheme.colorScheme
    val muteLocal by AppState.muteLocal.collectAsStateWithLifecycle()
    val nowPlayingOn by NowPlaying.enabled.collectAsStateWithLifecycle()
    val automationOn by AppState.automationAllowed.collectAsStateWithLifecycle()
    val report by AppState.report.collectAsStateWithLifecycle()
    val selected by AppState.selected.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var ip by remember { mutableStateOf("") }

    BackHandler(onBack = onClose)
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            Modifier.statusBarsPadding().fillMaxWidth().clickable(enabled = false) {},
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

                Column {
                    Text(stringResource(R.string.log_title), style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                    Text(stringResource(R.string.log_hint), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                    Row {
                        TextButton(onClick = { Diagnose.shareLog(context); onClose() }) { Text(stringResource(R.string.log_share)) }
                        TextButton(onClick = { Diagnose.saveLog(context); onClose() }) {
                            Text(stringResource(R.string.save_downloads))
                        }
                    }
                }

                HorizontalDivider(color = c.outlineVariant)

                Text(stringResource(R.string.more_ip), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = ip, onValueChange = { ip = it }, placeholder = { Text("192.168.x.x") },
                        singleLine = true, shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onAddManual(ip); ip = ""; onClose() }) { Text(stringResource(R.string.ok)) }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.more_mute), style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                        Text(stringResource(R.string.more_mute_hint),
                            style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                    }
                    Switch(
                        checked = muteLocal, enabled = !running,
                        onCheckedChange = { AppState.setMuteLocal(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = c.primary, checkedThumbColor = c.onPrimary)
                    )
                }

                SwitchRow(stringResource(R.string.more_now_playing), stringResource(R.string.more_now_playing_hint),
                    nowPlayingOn) { NowPlaying.setAccess(context, it) }
                SwitchRow(stringResource(R.string.more_automation), stringResource(R.string.more_automation_hint),
                    automationOn) { AppState.setAutomationAllowed(it) }

                HorizontalDivider(color = c.outlineVariant)

                if (selected?.protocol == SpeakerProtocol.SONOS) Hint(stringResource(R.string.hint_upnp))
                Hint(stringResource(R.string.hint_lossless))
                if (mode == CaptureMode.ALL_APPS) Hint(stringResource(R.string.hint_all_apps))

                if (report != null) {
                    HorizontalDivider(color = c.outlineVariant)
                    Text(stringResource(R.string.last_report), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                    Text(
                        report!!.lineSequence().drop(1).take(2).joinToString("\n") { it.substringAfter(":").trim() },
                        style = MaterialTheme.typography.bodyMedium, color = c.onSurface
                    )
                    Row {
                        TextButton(onClick = { Diagnose.share(context) }) { Text(stringResource(R.string.share)) }
                        TextButton(onClick = { Diagnose.saveToDownloads(context); onClose() }) {
                            Text(stringResource(R.string.save_downloads))
                        }
                    }
                }

                HorizontalDivider(color = c.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onLicenses) { Text(stringResource(R.string.licenses)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---- Pairing (All apps mode) --------------------------------------------

@Composable
private fun PairingDialog(onStartPairing: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val busy by AppState.adbBusy.collectAsStateWithLifecycle()
    val adbMessage by AppState.adbMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).pointerInput(Unit) {}.imePadding().padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = c.surfaceContainerHigh) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Text(stringResource(R.string.pair_steps),
                    style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                Text(stringResource(R.string.pair_hint),
                    style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)

                Text(stringResource(R.string.pair_code_hint),
                    style = MaterialTheme.typography.bodyMedium, color = c.onSurface)

                adbMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.primary) }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { AppState.requestPairing.value = false }) { Text(stringResource(R.string.close)) }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = onStartPairing,
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary)
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.onPrimary)
                        else Text(stringResource(R.string.pair_start))
                    }
                }
                // "⋯" is out of reach behind this dialog, and setup is when a log helps most.
                TextButton(onClick = { Diagnose.shareLog(context) }) { Text(stringResource(R.string.log_share)) }
            }
        }
    }
}
