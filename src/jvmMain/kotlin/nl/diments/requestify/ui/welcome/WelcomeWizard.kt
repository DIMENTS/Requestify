package nl.diments.requestify.ui.welcome

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nl.diments.requestify.core.settings.WizardStep
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.wizard_connect_donations
import nl.diments.requestify.resources.wizard_connect_spotify
import nl.diments.requestify.resources.wizard_connect_twitch
import nl.diments.requestify.resources.wizard_connected
import nl.diments.requestify.resources.wizard_continue
import nl.diments.requestify.resources.wizard_connected_as
import nl.diments.requestify.resources.wizard_done_connected
import nl.diments.requestify.resources.wizard_done_connected_plain
import nl.diments.requestify.resources.wizard_done_later
import nl.diments.requestify.resources.wizard_done_title
import nl.diments.requestify.resources.wizard_done_tray
import nl.diments.requestify.resources.wizard_last_one
import nl.diments.requestify.resources.wizard_later
import nl.diments.requestify.resources.wizard_lets_go
import nl.diments.requestify.resources.wizard_open
import nl.diments.requestify.resources.wizard_platform_donations
import nl.diments.requestify.resources.wizard_remaining
import nl.diments.requestify.resources.wizard_skip
import nl.diments.requestify.resources.wizard_welcome_body
import nl.diments.requestify.resources.wizard_welcome_title
import nl.diments.requestify.ui.common.AppIcons
import nl.diments.requestify.ui.common.PlatformLogo
import nl.diments.requestify.ui.common.PlatformMark
import nl.diments.requestify.ui.setup.DonationTest
import nl.diments.requestify.ui.setup.DonationsSetupContent
import nl.diments.requestify.ui.setup.DonationsSetupViewModel
import nl.diments.requestify.ui.setup.SpotifySetupContent
import nl.diments.requestify.ui.setup.SpotifySetupViewModel
import nl.diments.requestify.ui.setup.TwitchCode
import nl.diments.requestify.ui.setup.TwitchSetupContent
import nl.diments.requestify.ui.setup.TwitchSetupViewModel
import nl.diments.requestify.ui.theme.LocalReducedMotion
import nl.diments.requestify.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

const val WIZARD_TAG = "wizard"

fun wizardBadgeTag(platform: WizardPlatform) = "wizard-badge-${platform.name.lowercase()}"

/** How long a connection is celebrated on its card before the next one comes. */
internal const val CELEBRATION_MS = 2_000L

/** The widest the wizard's card gets: one card, centred, with room around it. */
private val CardMaxWidth = 640.dp

internal val WizardPlatform.mark: PlatformMark
    get() = when (this) {
        WizardPlatform.Spotify -> PlatformMark.Spotify
        WizardPlatform.Twitch -> PlatformMark.Twitch
        WizardPlatform.Donations -> PlatformMark.Donations
    }

@Composable
internal fun WizardPlatform.displayName(): String = when (this) {
    WizardPlatform.Spotify -> "Spotify"
    WizardPlatform.Twitch -> "Twitch"
    WizardPlatform.Donations -> stringResource(Res.string.wizard_platform_donations)
}

/** Which card shows: a step, or the celebration of the platform that just connected on it. */
private data class CardKey(val step: WizardStep, val celebrating: WizardPlatform?)

/**
 * The welcome wizard (direction B): one card at a time in the middle of the window, the progress dots and the badges
 * of what is connected above it. The platform cards are the Connections page's own setup ([SpotifySetupContent],
 * [TwitchSetupContent], [DonationsSetupContent]); "Later doen" stays in view at the bottom of each, also in a small
 * window, while the card's content scrolls.
 */
@Composable
fun WelcomeWizard(vm: WelcomeWizardViewModel, spotify: SpotifySetupViewModel, twitch: TwitchSetupViewModel, donations: DonationsSetupViewModel) {
    val state by vm.state.collectAsState()
    val donationsState by donations.state.collectAsState()
    val twitchSettings by twitch.settings.collectAsState()
    val accounts: Map<WizardPlatform, String?> = mapOf(
        WizardPlatform.Twitch to twitchSettings.twitch.account?.displayName,
        WizardPlatform.Donations to (donationsState.test as? DonationTest.Ok)?.name,
    )
    val reduced = LocalReducedMotion.current
    // Where the logo flies from (its card's header) and to (the badge row), in the wizard's own space.
    var origin by remember { mutableStateOf(Offset.Zero) }
    var headerLogo by remember { mutableStateOf<Offset?>(null) }
    var badgeRowEnd by remember { mutableStateOf<Offset?>(null) }
    var flying by remember { mutableStateOf<WizardPlatform?>(null) }
    var landed by remember { mutableStateOf<WizardPlatform?>(null) }
    LaunchedEffect(state.celebrating) {
        val celebrating = state.celebrating ?: return@LaunchedEffect
        if (reduced) {
            vm.celebrated() // nothing moves: straight on
        } else {
            flying = celebrating
            delay(CELEBRATION_MS)
            flying = null // landed by now; also when the flight could not start, the badge must show
            vm.celebrated()
        }
    }
    Box(Modifier.fillMaxSize().testTag(WIZARD_TAG).onGloballyPositioned { origin = it.positionInRoot() }, contentAlignment = Alignment.TopCenter) {
        if (!reduced) MovingShapes(Modifier.matchParentSize())
        Column(Modifier.widthIn(max = CardMaxWidth).fillMaxHeight().padding(horizontal = Spacing.xl, vertical = Spacing.l)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                ProgressDots(state.step)
                Spacer(Modifier.weight(1f))
                BadgeRow(
                    connected = state.connected - setOfNotNull(flying), // a flying logo becomes its badge when it lands
                    landed = landed.takeIf { !reduced },
                    modifier = Modifier.onGloballyPositioned { badgeRowEnd = it.positionInRoot() + Offset(it.size.width.toFloat(), 0f) },
                )
            }
            Spacer(Modifier.height(Spacing.m))
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.weight(1f).fillMaxWidth().squashOn(state.celebrating, enabled = !reduced),
            ) {
                AnimatedContent(
                    targetState = CardKey(state.step, state.celebrating),
                    transitionSpec = {
                        if (reduced) {
                            fadeIn().togetherWith(fadeOut())
                        } else {
                            val bouncy = spring<IntOffset>(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow)
                            (slideInHorizontally(bouncy) { it / 3 } + fadeIn() + scaleIn(initialScale = 0.92f))
                                .togetherWith(slideOutHorizontally { -it / 4 } + fadeOut())
                        }
                    },
                ) { key ->
                    Box(Modifier.fillMaxSize().tiltIn(enabled = !reduced)) {
                        when {
                            key.celebrating != null -> Celebration(key.celebrating, accounts[key.celebrating], state.remaining, !reduced)
                            key.step == WizardStep.Welcome -> WelcomeCard(onBegin = vm::begin, onSkip = vm::skipAll)
                            key.step == WizardStep.Done -> DoneCard(state.connected, accounts, !reduced, onOpen = vm::finish)
                            else -> PlatformCard(
                                key.step,
                                connected = key.step.platform in state.connected,
                                onLater = vm::later,
                                onContinue = vm::continueOn,
                                onLogoPositioned = { headerLogo = it },
                            ) {
                                when (key.step) {
                                    WizardStep.Spotify -> SpotifySetupContent(spotify.state.collectAsState().value, spotify)
                                    // The Twitch content brings its own side margins (its card has none): cancel the wizard's.
                                    WizardStep.Twitch -> Box(Modifier.bleedHorizontally(Spacing.xl)) {
                                        TwitchSetupContent(twitch, code = { if (reduced) TwitchCode(it) else PoppingCode(it) })
                                    }
                                    else -> DonationsSetupContent(donationsState, donations)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!reduced) {
            state.celebrating?.let { ConfettiBurst(key = it, modifier = Modifier.matchParentSize()) }
            if (state.step == WizardStep.Done && state.celebrating == null) ConfettiBurst(key = WizardStep.Done, modifier = Modifier.matchParentSize())
            val from = headerLogo
            val to = badgeRowEnd
            val platform = flying
            if (platform != null && from != null && to != null) {
                Box(Modifier.matchParentSize()) {
                    LogoFlight(platform.mark, from = from - origin, to = to - origin - Offset(BADGE_LANDING_PX, 0f)) {
                        landed = platform
                        flying = null
                    }
                }
            }
        }
    }
}

/** Lets content reach [amount] past both sides of its parent's padding (content with side margins of its own). */
private fun Modifier.bleedHorizontally(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = amount.roundToPx() * 2
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** Where a landing logo ends: this far left of the badge row's end, where its badge appears. */
private const val BADGE_LANDING_PX = 96f

@Composable
private fun ProgressDots(step: WizardStep) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        WizardStep.entries.forEach { dot ->
            val width by animateDpAsState(if (dot == step) 24.dp else 8.dp)
            val colour = when {
                dot == step -> MaterialTheme.colorScheme.primary
                dot.ordinal < step.ordinal -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            Box(Modifier.height(8.dp).width(width).background(colour, CircleShape))
        }
    }
}

@Composable
private fun BadgeRow(connected: Set<WizardPlatform>, landed: WizardPlatform?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        WizardPlatform.entries.filter { it in connected }.forEach { platform ->
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.testTag(wizardBadgeTag(platform)).wiggleOnce(enabled = platform == landed),
            ) {
                Row(Modifier.padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlatformLogo(platform.mark, Modifier.padding(end = 6.dp), size = 18.dp)
                    Text(platform.displayName(), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** The mark of a card: the app glyph on the welcome card, a check once something is connected. */
@Composable
private fun Mark(done: Boolean, pop: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(88.dp).popIn(pop).background(if (done) scheme.tertiaryContainer else scheme.primary, RoundedCornerShape(28.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (done) AppIcons.CheckBold else AppIcons.RequestifyGlyph,
            contentDescription = null,
            tint = if (done) scheme.onTertiaryContainer else scheme.onPrimary,
            modifier = Modifier.size(48.dp),
        )
    }
}

@Composable
private fun Centred(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.l, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun WelcomeCard(onBegin: () -> Unit, onSkip: () -> Unit) = Centred {
    Mark(done = false)
    Text(stringResource(Res.string.wizard_welcome_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Text(
        stringResource(Res.string.wizard_welcome_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 460.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onBegin) { Text(stringResource(Res.string.wizard_lets_go)) }
        TextButton(onClick = onSkip) { Text(stringResource(Res.string.wizard_skip)) }
    }
}

@Composable
private fun PlatformCard(
    step: WizardStep,
    connected: Boolean,
    onLater: () -> Unit,
    onContinue: () -> Unit,
    onLogoPositioned: (Offset) -> Unit,
    content: @Composable () -> Unit,
) {
    val platform = checkNotNull(step.platform)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformLogo(
                    platform.mark,
                    Modifier.onGloballyPositioned { onLogoPositioned(it.positionInRoot()) }.padding(end = Spacing.m),
                    size = 36.dp,
                )
                Text(
                    stringResource(
                        when (platform) {
                            WizardPlatform.Spotify -> Res.string.wizard_connect_spotify
                            WizardPlatform.Twitch -> Res.string.wizard_connect_twitch
                            WizardPlatform.Donations -> Res.string.wizard_connect_donations
                        },
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            content()
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
            // Connected (an Affiliate stays for the Channel Points reward): on to the next card. Else: later.
            if (connected) {
                Button(onClick = onContinue) { Text(stringResource(Res.string.wizard_continue)) }
            } else {
                TextButton(onClick = onLater) { Text(stringResource(Res.string.wizard_later)) }
            }
        }
    }
}

@Composable
private fun Celebration(platform: WizardPlatform, account: String?, remaining: Int, motion: Boolean) = Centred {
    Mark(done = true, pop = motion)
    Text(stringResource(Res.string.wizard_connected, platform.displayName()), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    account?.let { Text(stringResource(Res.string.wizard_connected_as, it), style = MaterialTheme.typography.bodyLarge) }
    Text(
        if (remaining > 0) stringResource(Res.string.wizard_remaining, remaining) else stringResource(Res.string.wizard_last_one),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DoneCard(connected: Set<WizardPlatform>, accounts: Map<WizardPlatform, String?>, motion: Boolean, onOpen: () -> Unit) = Centred {
    Mark(done = true, pop = motion)
    Text(stringResource(Res.string.wizard_done_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        WizardPlatform.entries.forEach { platform ->
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                    PlatformLogo(platform.mark, Modifier.padding(end = Spacing.m).dancing(platform.ordinal, enabled = motion))
                    Column {
                        Text(platform.displayName(), style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                platform !in connected -> stringResource(Res.string.wizard_done_later)
                                accounts[platform] != null -> stringResource(Res.string.wizard_done_connected, accounts.getValue(platform)!!)
                                else -> stringResource(Res.string.wizard_done_connected_plain)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (platform in connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    Text(
        stringResource(Res.string.wizard_done_tray),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 460.dp),
    )
    Button(onClick = onOpen) { Text(stringResource(Res.string.wizard_open)) }
}
