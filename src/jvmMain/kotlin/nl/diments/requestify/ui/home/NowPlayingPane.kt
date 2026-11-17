package nl.diments.requestify.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupScope
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.delay
import nl.diments.requestify.core.model.Request
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.playback.NowPlaying
import nl.diments.requestify.core.playback.NowPlayingKind
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.now_playing_idle_body
import nl.diments.requestify.resources.now_playing_idle_title
import nl.diments.requestify.resources.now_playing_label
import nl.diments.requestify.resources.now_playing_label_last_played
import nl.diments.requestify.resources.now_playing_label_paused
import nl.diments.requestify.resources.now_playing_no_time
import nl.diments.requestify.resources.now_playing_own_music
import nl.diments.requestify.resources.now_playing_pause
import nl.diments.requestify.resources.now_playing_play
import nl.diments.requestify.resources.now_playing_previous
import nl.diments.requestify.resources.now_playing_skip
import nl.diments.requestify.ui.common.AlbumArt
import nl.diments.requestify.ui.common.AppIcons
import nl.diments.requestify.ui.common.formatDuration
import nl.diments.requestify.ui.common.requesterLabel
import nl.diments.requestify.ui.common.sourceLabel
import nl.diments.requestify.ui.theme.SourceChip
import nl.diments.requestify.ui.theme.Spacing
import nl.diments.requestify.ui.theme.sourceChipHeight
import org.jetbrains.compose.resources.stringResource

/** The Now Playing card's test tag. */
internal const val NOW_PLAYING_PANE_TAG = "now-playing"

/** The test tag of the room kept for the track details (see [Details]). */
internal const val NOW_PLAYING_DETAILS_TAG = "now-playing-details"

private val PaneCorner = 32.dp

/** The smallest the wide card's art gets before the card scrolls instead. */
private val MinArtSide = 64.dp

/** Asymmetric art corners (top-start, top-end, bottom-end, bottom-start). */
private val ArtCorners = listOf(36.dp, 36.dp, 12.dp, 36.dp)

/** The title's line height relative to its size: tighter than the type scale's, for a two-line display title. */
private const val TITLE_LINE_HEIGHT = 1.15f

/**
 * Right side of Home: what Spotify plays now (or played last), who asked for it, live progress and the previous,
 * play/pause and skip group, all on one start edge. The details are equally tall in every state, nothing playing
 * included (see [Details]), so switching songs or going idle never moves the progress or the buttons. The art is
 * on top; the track info, progress and controls sit at the bottom of the card and the art takes the height they
 * leave (square, at most the card's width); once the art is that wide, the whole stack is centred instead.
 * [compact] is for narrow windows, where the pane sits above the queue: the art beside the track info and as tall
 * as it, and under them the controls with the progress filling the rest of the row. [clock] is wall time, the
 * clock [NowPlaying.sampledAtEpochMs] is on.
 */
@Composable
fun NowPlayingPane(
    nowPlaying: NowPlaying?,
    onPrevious: () -> Unit,
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
        val details = @Composable { Details(nowPlaying, compact) }
        val progress = @Composable { Progress(nowPlaying, clock) }
        val controls = @Composable {
            Controls(
                isPlaying = nowPlaying?.isPlaying == true,
                enabled = nowPlaying != null,
                onPrevious = onPrevious,
                onTogglePause = onTogglePause,
                onSkip = onSkip,
            )
        }
        if (compact) {
            Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    art(Modifier.size(detailsHeight(compact = true)))
                    Box(Modifier.weight(1f)) { details() }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    controls()
                    Box(Modifier.weight(1f)) { progress() }
                }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize().padding(Spacing.xl)) {
                val available = constraints.maxHeight
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    ArtOverDetails(available) {
                        art(Modifier)
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                            details()
                            progress()
                            controls()
                        }
                    }
                }
            }
        }
    }
}

/**
 * The wide card's stack: the art (first child) over the details (second child), left-aligned. The details keep
 * their natural height and the art takes what is left of [availableHeight] (px), square and at most the width, so
 * the art is the one that gives way; the stack is centred when the art is as wide as it can get. The art shrinks
 * down to [MinArtSide]; if the details need more than that leaves, the stack grows taller than [availableHeight]
 * and the enclosing scroll container reaches the rest.
 */
@Composable
private fun ArtOverDetails(availableHeight: Int, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = Spacing.l.roundToPx()
        val details = measurables[1].measure(Constraints(maxWidth = width))
        val side = minOf(width, maxOf(MinArtSide.roundToPx(), availableHeight - details.height - gap))
        val art = measurables[0].measure(Constraints.fixed(side, side))
        val stack = art.height + gap + details.height
        val height = maxOf(stack, availableHeight)
        layout(width, height) {
            val top = (height - stack) / 2
            art.place(0, top)
            details.place(0, top + art.height + gap)
        }
    }
}

/**
 * The label, title, artist and who asked for it, in a block of [detailsHeight] whatever they hold, anchored at its
 * bottom: a one-line title leaves its spare line above the label, so the text stays together and nothing under it
 * moves. With nothing playing the block holds the label, the idle title and how to get something here.
 */
@Composable
private fun Details(nowPlaying: NowPlaying?, compact: Boolean) {
    Box(
        Modifier.height(detailsHeight(compact)).testTag(NOW_PLAYING_DETAILS_TAG),
        contentAlignment = Alignment.BottomStart,
    ) {
        // Measured at its own height, so nothing in it is ever squeezed (if it did not fit, it would show above).
        Column(
            Modifier.wrapContentHeight(Alignment.Bottom, unbounded = true),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Label(nowPlaying)
            if (nowPlaying == null) {
                Title(stringResource(Res.string.now_playing_idle_title), compact)
                Text(
                    stringResource(Res.string.now_playing_idle_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                TrackText(nowPlaying.track, compact)
                RequestLine(nowPlaying.request, nowPlaying.requesterKnown)
            }
        }
    }
}

/**
 * How tall the details are in every state: the label, two lines of title, the artist and the requester row with
 * the gaps between them, in whole pixels as they are laid out at the current density and text size, so they
 * always fit exactly. (Nothing playing needs less: its message takes at most two body lines where the artist and
 * the requester row go.)
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun detailsHeight(compact: Boolean): Dp {
    val measurer = rememberTextMeasurer()
    val typography = MaterialTheme.typography
    val title = titleStyle(compact)
    val requesterRow = requesterRowHeight()
    return with(LocalDensity.current) {
        val px = measurer.linesHeight(typography.labelMediumEmphasized, 1) + Spacing.s.roundToPx() +
            measurer.linesHeight(title, 2) + measurer.linesHeight(typography.titleMedium, 1) +
            Spacing.s.roundToPx() + requesterRow.roundToPx()
        px.toDp()
    }
}

/** How many pixels [lines] lines of [style] take, measured the way a Text lays them out. */
private fun TextMeasurer.linesHeight(style: TextStyle, lines: Int): Int =
    measure(List(lines) { "H" }.joinToString("\n"), style).size.height

/** NOW PLAYING while playing (or with nothing to show), PAUSED while paused, LAST PLAYED for the last song played. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Label(nowPlaying: NowPlaying?) {
    val label = when {
        nowPlaying?.kind == NowPlayingKind.LastPlayed -> Res.string.now_playing_label_last_played
        nowPlaying == null || nowPlaying.isPlaying -> Res.string.now_playing_label
        else -> Res.string.now_playing_label_paused
    }
    Crossfade(label, animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()) { shown ->
        Text(
            stringResource(shown),
            style = MaterialTheme.typography.labelMediumEmphasized,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.2.sp,
            maxLines = 1,
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
            Title(shown.name, compact)
            Text(
                shown.artists.joinToString(", "),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** At most two lines (the details keep room for two); a longer title ends in "…". */
@Composable
private fun Title(text: String, compact: Boolean) {
    Text(text, style = titleStyle(compact), maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun titleStyle(compact: Boolean): TextStyle {
    val typography = MaterialTheme.typography
    val style = if (compact) typography.headlineMediumEmphasized else typography.displaySmallEmphasized
    return style.copy(lineHeight = style.fontSize * TITLE_LINE_HEIGHT)
}

/**
 * Who asked for the song (its source chip and name), or that it is the streamer's own music; when that is not
 * [known], the row stays empty (its room is kept).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RequestLine(request: Request?, known: Boolean) {
    val motion = MaterialTheme.motionScheme
    Box(Modifier.height(requesterRowHeight()), contentAlignment = Alignment.CenterStart) {
        AnimatedContent(
            targetState = request to known,
            contentKey = { (shown, shownKnown) -> shown?.id ?: shownKnown },
            transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier.fillMaxHeight(),
        ) { (shown, shownKnown) ->
            if (shown != null) {
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
            } else if (shownKnown) {
                Text(
                    stringResource(Res.string.now_playing_own_music),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The requester row is as tall as a source chip (or a line of the name beside it), with a chip or without. */
@Composable
private fun requesterRowHeight(): Dp {
    val nameLine = rememberTextMeasurer().linesHeight(MaterialTheme.typography.labelLarge, 1)
    return with(LocalDensity.current) { maxOf(sourceChipHeight().roundToPx(), nameLine).toDp() }
}

/**
 * The wavy bar flattens while paused; between polls the position runs on locally. With nothing playing the bar
 * is empty and the times are placeholders, as in Spotify (an empty text lays out a pixel shorter at some text
 * sizes, which would move what is under it).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Progress(nowPlaying: NowPlaying?, clock: () -> Long) {
    val durationMs = nowPlaying?.track?.durationMs ?: 0L
    val position = rememberLivePosition(nowPlaying, clock)
    val elapsed by remember { derivedStateOf { formatDuration(position.longValue) } }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        LinearWavyProgressIndicator(
            progress = { if (durationMs > 0) position.longValue.toFloat() / durationMs else 0f },
            modifier = Modifier.fillMaxWidth(),
            amplitude = { if (nowPlaying?.isPlaying == true) WavyProgressIndicatorDefaults.indicatorAmplitude(it) else 0f },
        )
        val timeStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
        val timeColor = MaterialTheme.colorScheme.onSurfaceVariant
        val noTime = stringResource(Res.string.now_playing_no_time)
        Row {
            Text(if (nowPlaying == null) noTime else elapsed, style = timeStyle, color = timeColor)
            Spacer(Modifier.weight(1f))
            Text(if (nowPlaying == null) noTime else formatDuration(durationMs), style = timeStyle, color = timeColor)
        }
    }
}

/**
 * The position on [clock] (see [NowPlaying.positionAt]), updated every [PROGRESS_TICK_MS] on the frame clock while
 * playing, until the track's end; 0 with nothing playing. It runs from when Spotify sampled it, so a new poll
 * moves the bar only as far as Spotify really differs, and a pause stops it where it is. (The bar moves about a
 * pixel per second, so ticking every frame would look no smoother.)
 */
@Composable
private fun rememberLivePosition(nowPlaying: NowPlaying?, clock: () -> Long): LongState {
    val now by rememberUpdatedState(clock)
    val position = remember { mutableLongStateOf(nowPlaying?.positionAt(now()) ?: 0L) }
    LaunchedEffect(nowPlaying) {
        position.longValue = nowPlaying?.positionAt(now()) ?: 0L
        if (nowPlaying == null) return@LaunchedEffect
        while (nowPlaying.isPlaying && position.longValue < nowPlaying.track.durationMs) {
            delay(PROGRESS_TICK_MS)
            withFrameMillis { position.longValue = nowPlaying.positionAt(now()) }
        }
    }
    return position
}

private const val PROGRESS_TICK_MS = 250L

/**
 * Previous, play/pause (primary, wide; round while paused, squarer while playing) and skip, as an Expressive
 * button group. With nothing to control they stay where they are, disabled.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Controls(
    isPlaying: Boolean,
    enabled: Boolean,
    onPrevious: () -> Unit,
    onTogglePause: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playPauseLabel = stringResource(if (isPlaying) Res.string.now_playing_pause else Res.string.now_playing_play)
    val previousLabel = stringResource(Res.string.now_playing_previous)
    val skipLabel = stringResource(Res.string.now_playing_skip)
    val motion = MaterialTheme.motionScheme
    val scheme = MaterialTheme.colorScheme
    ButtonGroup(overflowIndicator = {}, modifier = modifier) {
        tonalItem(AppIcons.SkipPrevious, previousLabel, enabled, onPrevious)
        customItem(
            buttonGroupContent = {
                val interaction = remember { MutableInteractionSource() }
                FilledIconToggleButton(
                    checked = isPlaying,
                    onCheckedChange = { onTogglePause() },
                    enabled = enabled,
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
                DropdownMenuItem(
                    text = { Text(playPauseLabel) },
                    onClick = { onTogglePause(); menu.dismiss() },
                    enabled = enabled,
                )
            },
        )
        tonalItem(AppIcons.SkipNext, skipLabel, enabled, onSkip)
    }
}

/** A round tonal button of the group (previous, skip), offered in the group's menu too when it does not fit. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun ButtonGroupScope.tonalItem(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    customItem(
        buttonGroupContent = {
            val interaction = remember { MutableInteractionSource() }
            FilledTonalIconButton(
                onClick = onClick,
                enabled = enabled,
                shapes = IconButtonDefaults.shapes(),
                interactionSource = interaction,
                modifier = Modifier
                    .size(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Uniform))
                    .animateWidth(interaction),
            ) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(IconButtonDefaults.mediumIconSize))
            }
        },
        menuContent = { menu ->
            DropdownMenuItem(text = { Text(label) }, onClick = { onClick(); menu.dismiss() }, enabled = enabled)
        },
    )
}
