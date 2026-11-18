package nl.diments.requestify.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import java.awt.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import nl.diments.requestify.AppGraph
import nl.diments.requestify.core.model.Track
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.spotify.auth.SpotifyAuthStatus
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.nav_add
import nl.diments.requestify.resources.nav_blacklist
import nl.diments.requestify.resources.nav_log
import nl.diments.requestify.resources.nav_connections
import nl.diments.requestify.resources.nav_home
import nl.diments.requestify.resources.nav_rules
import nl.diments.requestify.resources.nav_settings
import nl.diments.requestify.resources.settings_load_failed
import nl.diments.requestify.ui.common.AppIcons
import nl.diments.requestify.ui.common.ProblemBanner
import nl.diments.requestify.ui.common.connectionProblems
import nl.diments.requestify.ui.blacklist.BlacklistScreen
import nl.diments.requestify.ui.home.HomeScreen
import nl.diments.requestify.ui.log.LogScreen
import nl.diments.requestify.ui.rules.RulesScreen
import nl.diments.requestify.ui.settings.SettingsScreen
import nl.diments.requestify.ui.setup.SpotifySetupScreen
import nl.diments.requestify.ui.setup.isValidSpotifyClientId
import nl.diments.requestify.ui.theme.RequestifyTheme
import nl.diments.requestify.ui.theme.Spacing
import nl.diments.requestify.ui.theme.albumSeedFrom
import nl.diments.requestify.ui.theme.albumSeedOrPrevious
import nl.diments.requestify.ui.theme.resolveDark
import nl.diments.requestify.ui.theme.selectSeeds
import nl.diments.requestify.ui.window.RequestsToggle
import nl.diments.requestify.ui.window.TitleBar
import nl.diments.requestify.ui.window.rememberWindowCaption
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The app's top-level screens, in rail order. */
enum class Destination(val label: StringResource, val icon: ImageVector) {
    Home(Res.string.nav_home, AppIcons.Home),
    Connections(Res.string.nav_connections, AppIcons.Link),
    Rules(Res.string.nav_rules, AppIcons.Tune),
    Blacklist(Res.string.nav_blacklist, AppIcons.Block),
    Log(Res.string.nav_log, AppIcons.History),
    Settings(Res.string.nav_settings, AppIcons.Settings),
}

/** Spotify still needs setting up (no valid Client ID, or not logged in): start on Connections, else on Home. */
fun startDestination(settings: Settings, auth: SpotifyAuthStatus): Destination =
    if (isValidSpotifyClientId(settings.spotifyClientId.orEmpty()) && auth == SpotifyAuthStatus.LoggedIn) {
        Destination.Home
    } else {
        Destination.Connections
    }

/** Album art is scaled down to this many pixels per side before its seed colour is picked. */
private const val SEED_ART_SIZE_PX = 128

/**
 * The window's content: the title bar over the navigation rail and the current screen, all themed from the
 * album art (or the custom colour). Screens keep their state while another one is shown. On the JetBrains
 * Runtime the title bar replaces [window]'s own; otherwise (or without a window, as in tests) it is a header.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppScaffold(graph: AppGraph, window: Frame? = null) {
    val settings by graph.settings.settings.collectAsState()
    val settingsUnreadable by graph.settings.loadFailed.collectAsState()
    val track by remember(graph) { graph.home.state.map { it.nowPlaying?.track }.distinctUntilChanged() }
        .collectAsState(graph.home.state.value.nowPlaying?.track)
    val health by remember(graph) { graph.home.state.map { it.health }.distinctUntilChanged() }
        .collectAsState(graph.home.state.value.health)
    val gate by graph.gate.status.collectAsState()
    val twitchHealth by graph.twitchSession.health.collectAsState()
    val albumSeed = rememberAlbumSeed(track)
    val dark = resolveDark(settings.darkMode, isSystemInDarkTheme())
    val caption = rememberWindowCaption(window, dark)

    var destination by remember { mutableStateOf(startDestination(settings, graph.auth.status.value)) }
    val addFieldFocus = remember { FocusRequester() }
    var focusAddField by remember { mutableStateOf(false) }
    val screens = rememberSaveableStateHolder()

    RequestifyTheme(selectSeeds(settings.themeMode, albumSeed).app, dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column {
                TitleBar(
                    problems = connectionProblems(health, twitchHealth),
                    captionInset = caption?.buttonsInset ?: 0.dp,
                    onHitTest = caption?.hitTest,
                    onConnectionsClick = { destination = Destination.Connections },
                    requestsSwitch = { RequestsToggle(gate, onToggle = graph.gate::toggle) },
                )
                Row(Modifier.weight(1f)) {
                    AppRail(
                        selected = destination,
                        onSelect = { destination = it },
                        onAdd = {
                            destination = Destination.Home
                            focusAddField = true
                        },
                    )
                    val motion = MaterialTheme.motionScheme
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        // The defaults are in use and nothing is saved over the file; a later change reads it again.
                        AnimatedVisibility(
                            visible = settingsUnreadable,
                            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
                        ) {
                            ProblemBanner(
                                stringResource(Res.string.settings_load_failed),
                                Modifier.padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.xl),
                            )
                        }
                        AnimatedContent(
                            targetState = destination,
                            transitionSpec = {
                                (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.96f))
                                    .togetherWith(fadeOut(motion.fastEffectsSpec()))
                            },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) { shown ->
                            screens.SaveableStateProvider(shown.name) {
                                when (shown) {
                                    Destination.Home -> {
                                        HomeScreen(graph.home, albumSeed, addFieldFocus)
                                        LaunchedEffect(focusAddField) {
                                            if (focusAddField) {
                                                addFieldFocus.requestFocus()
                                                focusAddField = false
                                            }
                                        }
                                    }
                                    Destination.Connections -> SpotifySetupScreen(graph.spotifySetup, graph.twitchSetup)
                                    Destination.Rules -> RulesScreen(graph.settings)
                                    Destination.Blacklist -> BlacklistScreen(graph.blacklistScreen)
                                    Destination.Log -> LogScreen(graph.logScreen)
                                    Destination.Settings -> SettingsScreen(graph.settings)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The collapsed M3 navigation rail's width; its items are centred in it. */
internal val CollapsedRailWidth = 96.dp

/**
 * The Expressive navigation rail (collapsed), under the title bar: the "Add" FAB on top, then one item per
 * destination, all centred on one vertical line. The rail itself keeps the M3 gap between the FAB and the items.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppRail(selected: Destination, onSelect: (Destination) -> Unit, onAdd: () -> Unit) {
    WideNavigationRail(
        header = {
            // The rail places its header at its start edge; centre the FAB over the items.
            Box(Modifier.width(CollapsedRailWidth), contentAlignment = Alignment.Center) {
                val addLabel = stringResource(Res.string.nav_add)
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Right),
                    tooltip = { PlainTooltip { Text(addLabel) } },
                    state = rememberTooltipState(),
                ) {
                    FloatingActionButton(onClick = onAdd) {
                        Icon(AppIcons.Add, contentDescription = addLabel)
                    }
                }
            }
        },
        contentPadding = PaddingValues(vertical = Spacing.m),
    ) {
        Destination.entries.forEach { destination ->
            WideNavigationRailItem(
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.label)) },
                railExpanded = false,
            )
        }
    }
}

/**
 * The seed colour of [track]'s album art, picked off the main thread once per track. Until the new art is
 * ready the previous colour stays, so the scheme moves straight from album to album; it also stays when the
 * art cannot be decoded. Null without art.
 */
@Composable
private fun rememberAlbumSeed(track: Track?): Color? {
    val context = LocalPlatformContext.current
    val seed by produceState<Color?>(initialValue = null, track?.id) {
        val url = track?.albumArtUrl
        value = if (url == null) {
            null
        } else {
            albumSeedOrPrevious(value) {
                val request = ImageRequest.Builder(context).data(url).size(SEED_ART_SIZE_PX).build()
                val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image
                image?.let { withContext(Dispatchers.Default) { albumSeedFrom(it.toBitmap().asComposeImageBitmap()) } }
            }
        }
    }
    return seed
}
