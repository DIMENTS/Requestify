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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import nl.diments.requestify.resources.nav_connections
import nl.diments.requestify.resources.nav_home
import nl.diments.requestify.resources.nav_settings
import nl.diments.requestify.resources.settings_load_failed
import nl.diments.requestify.ui.common.AppIcons
import nl.diments.requestify.ui.common.ProblemBanner
import nl.diments.requestify.ui.home.HomeScreen
import nl.diments.requestify.ui.settings.SettingsScreen
import nl.diments.requestify.ui.setup.SpotifySetupScreen
import nl.diments.requestify.ui.setup.isValidSpotifyClientId
import nl.diments.requestify.ui.theme.RequestifyTheme
import nl.diments.requestify.ui.theme.albumSeedFrom
import nl.diments.requestify.ui.theme.resolveDark
import nl.diments.requestify.ui.theme.selectSeeds
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The app's top-level screens, in rail order. Later plans add Rules, Blacklist and Log. */
enum class Destination(val label: StringResource, val icon: ImageVector) {
    Home(Res.string.nav_home, AppIcons.Home),
    Connections(Res.string.nav_connections, AppIcons.Link),
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
 * The window's content: the navigation rail and the current screen, all themed from the album art (or the
 * custom colour). Screens keep their state while another one is shown.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppScaffold(graph: AppGraph) {
    val settings by graph.settings.settings.collectAsState()
    val settingsUnreadable by graph.settings.loadFailed.collectAsState()
    val track by remember(graph) { graph.home.state.map { it.nowPlaying?.track }.distinctUntilChanged() }
        .collectAsState(graph.home.state.value.nowPlaying?.track)
    val albumSeed = rememberAlbumSeed(track)
    val dark = resolveDark(settings.darkMode, isSystemInDarkTheme())

    var destination by remember { mutableStateOf(startDestination(settings, graph.auth.status.value)) }
    val addFieldFocus = remember { FocusRequester() }
    var focusAddField by remember { mutableStateOf(false) }
    val screens = rememberSaveableStateHolder()

    RequestifyTheme(selectSeeds(settings.themeMode, albumSeed).app, dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Row {
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
                            Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp),
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
                                Destination.Connections -> SpotifySetupScreen(graph.spotifySetup)
                                Destination.Settings -> SettingsScreen(graph.settings)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The Expressive navigation rail (collapsed): the "Add" FAB on top, then one item per destination. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppRail(selected: Destination, onSelect: (Destination) -> Unit, onAdd: () -> Unit) {
    WideNavigationRail(
        header = {
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
        },
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
 * ready the previous colour stays, so the scheme moves straight from album to album. Null without art.
 */
@Composable
private fun rememberAlbumSeed(track: Track?): Color? {
    val context = LocalPlatformContext.current
    val seed by produceState<Color?>(initialValue = null, track?.id) {
        val url = track?.albumArtUrl
        value = if (url == null) {
            null
        } else {
            val request = ImageRequest.Builder(context).data(url).size(SEED_ART_SIZE_PX).build()
            val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image
            image?.let { withContext(Dispatchers.Default) { albumSeedFrom(it.toBitmap().asComposeImageBitmap()) } }
        }
    }
    return seed
}
