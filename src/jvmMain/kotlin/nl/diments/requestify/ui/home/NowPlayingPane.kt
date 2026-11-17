package nl.diments.requestify.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.delay
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.NowPlaying
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.now_playing_idle_body
import nl.diments.requestify.resources.now_playing_idle_title
import nl.diments.requestify.resources.now_playing_label
import nl.diments.requestify.resources.now_playing_own_music
import nl.diments.requestify.resources.now_playing_pause
import nl.diments.requestify.resources.now_playing_play
import nl.diments.requestify.resources.now_playing_skip
import nl.diments.requestify.ui.common.AlbumArt
import nl.diments.requestify.ui.common.AppIcons
import nl.diments.requestify.ui.common.formatDuration
import nl.diments.requestify.ui.common.requesterLabel
import nl.diments.requestify.ui.common.sourceLabel
import nl.diments.requestify.ui.theme.SourceChip
import nl.diments.requestify.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/** The Now Playing card's test tag. */
internal const val NOW_PLAYING_PANE_TAG = "now-playing"

private val PaneCorner = 32.dp

/** Asymmetric art corners (top-start, top-end, bottom-end, bottom-start). */
private val ArtCorners = listOf(36.dp, 36.dp, 12.dp, 36.dp)

/** The art's side in the [compact] pane: about as tall as the track info beside it. */
private val CompactArtSize = 112.dp

/**
 * Right side of Home: what Spotify plays now, who asked for it, live progress and the play/pause + skip group,
 * all on one start edge. The art is on top; the track info, progress and controls sit at the bottom of the card
 * and the art takes the height they leave (square, at most the card's width); once the art is that wide, the
 * whole stack is centred instead. [compact] is for narrow windows, where the pane sits above the queue: the art
 * beside the track info, and under them the controls with the progress filling the rest of the row. [clock] is
 * wall time, the clock [NowPlaying.sampledAtEpochMs] is on.
 */
@Composable
fun NowPlayingPane(
    nowPlaying: NowPlaying?,
    onTogglePause: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    clock: () -> Long = System::currentTimeMillis,
) {
    Surface(
        modifier.testTag(NOW_PLAYING_PANE_TAG),
        shape = RoundedCornerShape(PaneCorner),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        val art = @Composable { artModifier: Modifier ->
            if (nowPlaying == null) {
                AlbumArt(null, artModifier.clip(artShape(1f)))
            } else {
                PoppingArt(nowPlaying.track, artModifier)
            }
        }
        val info = @Composable { if (nowPlaying == null) IdleInfo() else TrackInfo(nowPlaying, compact) }
        val controls = @Composable { playing: NowPlaying ->
            Controls(isPlaying = playing.isPlaying, onTogglePause = onTogglePause, onSkip = onSkip)
        }
        if (compact) {
            Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    art(Modifier.size(CompactArtSize))
                    Box(Modifier.weight(1f)) { info() }
                }
                if (nowPlaying != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.l),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        controls(nowPlaying)
                        Box(Modifier.weight(1f)) { Progress(nowPlaying, clock) }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.l, Alignment.CenterVertically),
            ) {
                BoxWithConstraints(Modifier.weight(1f, fill = false)) {
                    art(Modifier.size(min(maxWidth, maxHeight)))
                }
                info()
                if (nowPlaying != null) {
                    Progress(nowPlaying, clock)
                    controls(nowPlaying)
                }
            }
        }
    }
}

/** The label over the track and who asked for it. */
@Composable
private fun TrackInfo(nowPlaying: NowPlaying, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Label()
        TrackText(nowPlaying.track, compact)
        RequestLine(nowPlaying.request)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Label() {
    Text(
        stringResource(Res.string.now_playing_label),
        style = MaterialTheme.typography.labelMediumEmphasized,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 1.2.sp,
    )
}

/** Nothing plays: the label, and how to get something here. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IdleInfo() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Label()
        Text(stringResource(Res.string.now_playing_idle_title), style = MaterialTheme.typography.headlineSmallEmphasized)
        Text(
            stringResource(Res.string.now_playing_idle_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The art pops on every track change: it springs up from 86 % with a little overshoot while its shape
 * morphs from a circle into the asymmetric art shape.
 */
@Composable
private fun PoppingArt(track: Track, modifier: Modifier) {
    val pop = remember { Animatable(1f) }
    var shownId by remember { mutableStateOf(track.id) }
    LaunchedEffect(track.id) {
        if (track.id == shownId) return@LaunchedEffect
        shownId = track.id
        pop.snapTo(0f)
        pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow))
    }
    AlbumArt(
        track.albumArtUrl,
        modifier.graphicsLayer {
            val progress = pop.value
            scaleX = lerp(0.86f, 1f, progress)
            scaleY = scaleX
            shape = artShape(progress.coerceIn(0f, 1f))
            clip = true
        },
    )
}

/** The asymmetric art shape at [settled] = 1, a circle at 0. */
private fun artShape(settled: Float): Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val circle = size.minDimension / 2
        val (topStart, topEnd, bottomEnd, bottomStart) = ArtCorners.map { corner ->
            CornerSize(lerp(circle, with(density) { corner.toPx() }, settled))
        }
        return RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart).createOutline(size, layoutDirection, density)
    }
}

/** Title and artist rise into place when the track changes. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TrackText(track: Track, compact: Boolean) {
    val motion = MaterialTheme.motionScheme
    val typography = MaterialTheme.typography
    AnimatedContent(
        targetState = track,
        contentKey = { it.id },
        transitionSpec = {
            (fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it / 3 })
                .togetherWith(fadeOut(motion.fastEffectsSpec()))
                .using(SizeTransform(clip = false))
        },
    ) { shown ->
        Column {
            val titleStyle = if (compact) typography.headlineMediumEmphasized else typography.displaySmallEmphasized
            Text(
                shown.name,
                style = titleStyle.copy(lineHeight = 1.15.em),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = titleStyle.fontSize),
            )
            Text(
                shown.artists.joinToString(", "),
                style = typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RequestLine(request: Request?) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = request,
        contentKey = { it?.id },
        transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
    ) { shown ->
        if (shown == null) {
            Text(
                stringResource(Res.string.now_playing_own_music),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                SourceChip(shown.source, sourceLabel(shown))
                Text(
                    requesterLabel(shown),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The wavy bar flattens while paused; between polls the position runs on locally. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Progress(nowPlaying: NowPlaying, clock: () -> Long) {
    val durationMs = nowPlaying.track.durationMs
    val position = rememberLivePosition(nowPlaying, clock)
    val elapsed by remember { derivedStateOf { formatDuration(position.longValue) } }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        LinearWavyProgressIndicator(
            progress = { if (durationMs > 0) position.longValue.toFloat() / durationMs else 0f },
            modifier = Modifier.fillMaxWidth(),
            amplitude = { if (nowPlaying.isPlaying) WavyProgressIndicatorDefaults.indicatorAmplitude(it) else 0f },
        )
        val timeStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
        Row {
            Text(elapsed, style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(durationMs), style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The position on [clock] (see [NowPlaying.positionAt]), updated every [PROGRESS_TICK_MS] on the frame clock while
 * playing, until the track's end. It runs from when Spotify sampled it, so a new poll moves the bar only as far
 * as Spotify really differs, and a pause stops it where it is. (The bar moves about a pixel per second, so
 * ticking every frame would look no smoother.)
 */
@Composable
private fun rememberLivePosition(nowPlaying: NowPlaying, clock: () -> Long): LongState {
    val now by rememberUpdatedState(clock)
    val position = remember { mutableLongStateOf(nowPlaying.positionAt(now())) }
    LaunchedEffect(nowPlaying) {
        position.longValue = nowPlaying.positionAt(now())
        while (nowPlaying.isPlaying && position.longValue < nowPlaying.track.durationMs) {
            delay(PROGRESS_TICK_MS)
            withFrameMillis { position.longValue = nowPlaying.positionAt(now()) }
        }
    }
    return position
}

private const val PROGRESS_TICK_MS = 250L

/** Play/pause (primary, wide; round while paused, squarer while playing) and skip, as an Expressive button group. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Controls(isPlaying: Boolean, onTogglePause: () -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val playPauseLabel = stringResource(if (isPlaying) Res.string.now_playing_pause else Res.string.now_playing_play)
    val skipLabel = stringResource(Res.string.now_playing_skip)
    val motion = MaterialTheme.motionScheme
    val scheme = MaterialTheme.colorScheme
    ButtonGroup(overflowIndicator = {}, modifier = modifier) {
        customItem(
            buttonGroupContent = {
                val interaction = remember { MutableInteractionSource() }
                FilledIconToggleButton(
                    checked = isPlaying,
                    onCheckedChange = { onTogglePause() },
                    shapes = IconButtonDefaults.toggleableShapes(),
                    colors = IconButtonDefaults.filledIconToggleButtonColors(
                        containerColor = scheme.primary,
                        contentColor = scheme.onPrimary,
                        checkedContainerColor = scheme.primary,
                        checkedContentColor = scheme.onPrimary,
                    ),
                    interactionSource = interaction,
                    modifier = Modifier
                        .size(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Wide))
                        .animateWidth(interaction),
                ) {
                    AnimatedContent(
                        targetState = isPlaying,
                        transitionSpec = {
                            (scaleIn(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()))
                                .togetherWith(scaleOut(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()))
                        },
                    ) { playing ->
                        Icon(
                            if (playing) AppIcons.Pause else AppIcons.PlayArrow,
                            contentDescription = playPauseLabel,
                            modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
                        )
                    }
                }
            },
            menuContent = { menu ->
                DropdownMenuItem(text = { Text(playPauseLabel) }, onClick = { onTogglePause(); menu.dismiss() })
            },
        )
        customItem(
            buttonGroupContent = {
                val interaction = remember { MutableInteractionSource() }
                FilledTonalIconButton(
                    onClick = onSkip,
                    shapes = IconButtonDefaults.shapes(),
                    interactionSource = interaction,
                    modifier = Modifier
                        .size(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Uniform))
                        .animateWidth(interaction),
                ) {
                    Icon(
                        AppIcons.SkipNext,
                        contentDescription = skipLabel,
                        modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
                    )
                }
            },
            menuContent = { menu ->
                DropdownMenuItem(text = { Text(skipLabel) }, onClick = { onSkip(); menu.dismiss() })
            },
        )
    }
}
