package nl.diments.requestify.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Switch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.diments.requestify.resources.settings_start_hidden
import nl.diments.requestify.resources.settings_start_disabled_in_task_manager
import nl.diments.requestify.resources.settings_start_only_installed
import nl.diments.requestify.resources.settings_start_with_windows
import nl.diments.requestify.resources.settings_startup
import nl.diments.requestify.desktop.NoAutostart
import nl.diments.requestify.desktop.AutostartState
import nl.diments.requestify.desktop.Autostart
import nl.diments.requestify.core.settings.AppLanguage
import nl.diments.requestify.core.settings.DarkMode
import nl.diments.requestify.core.settings.Settings
import nl.diments.requestify.core.settings.SettingsStore
import nl.diments.requestify.core.settings.ThemeMode
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.nav_settings
import nl.diments.requestify.resources.save_failed
import nl.diments.requestify.resources.settings_appearance
import nl.diments.requestify.resources.settings_colour
import nl.diments.requestify.resources.settings_colour_album
import nl.diments.requestify.resources.settings_colour_album_hint
import nl.diments.requestify.resources.settings_colour_custom
import nl.diments.requestify.resources.settings_colour_custom_hint
import nl.diments.requestify.resources.settings_language
import nl.diments.requestify.resources.settings_language_dutch
import nl.diments.requestify.resources.settings_language_english
import nl.diments.requestify.resources.settings_language_restart
import nl.diments.requestify.resources.settings_swatch
import nl.diments.requestify.resources.settings_system
import nl.diments.requestify.resources.settings_theme
import nl.diments.requestify.resources.settings_theme_dark
import nl.diments.requestify.resources.settings_theme_light
import nl.diments.requestify.ui.common.AppIcons
import nl.diments.requestify.ui.common.PageScaffold
import nl.diments.requestify.ui.common.ProblemBanner
import nl.diments.requestify.ui.theme.BrandSeed
import nl.diments.requestify.ui.theme.Spacing
import nl.diments.requestify.ui.theme.appear
import nl.diments.requestify.ui.theme.disappear
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The custom colours to pick from: the brand violet, then hues spread around the colour wheel. */
internal val CustomSeedPresets: List<Color> = listOf(
    BrandSeed,
    Color(0xFF1E88E5), // blue
    Color(0xFF00897B), // teal
    Color(0xFF43A047), // green
    Color(0xFFFBC02D), // yellow
    Color(0xFFF4511E), // deep orange
    Color(0xFFE53935), // red
    Color(0xFFD81B60), // pink
)

private val SwatchSize = 48.dp

private enum class ColourSource { Album, Custom }

/** The "Start with Windows" switch, for tests. */
const val START_WITH_WINDOWS_TAG = "start-with-windows"

/**
 * Settings → Appearance and Start-up. Every change applies at once (language at the next start); one that cannot be
 * made because the settings cannot be read yet says so. Start with Windows follows Windows itself ([autostart]): it
 * is read and switched off the UI thread, and a choice is remembered so a start never undoes it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(settings: SettingsStore, autostart: Autostart = NoAutostart, about: AboutViewModel? = null) {
    val current by settings.settings.collectAsState()
    var saveFailed by remember { mutableStateOf(false) }
    fun save(change: (Settings) -> Settings) {
        saveFailed = !settings.tryUpdate(change)
    }
    val motion = MaterialTheme.motionScheme
    PageScaffold(stringResource(Res.string.nav_settings)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                Text(stringResource(Res.string.settings_appearance), style = MaterialTheme.typography.titleLargeEmphasized)
                AnimatedVisibility(
                    visible = saveFailed,
                    enter = motion.appear(),
                    exit = motion.disappear(),
                ) {
                    ProblemBanner(stringResource(Res.string.save_failed))
                }
                ColourSetting(current.themeMode) { mode -> save { it.copy(themeMode = mode) } }
                Setting(Res.string.settings_theme) {
                    ChoiceGroup(
                        options = DarkMode.entries,
                        selected = current.darkMode,
                        label = {
                            when (it) {
                                DarkMode.System -> Res.string.settings_system
                                DarkMode.Light -> Res.string.settings_theme_light
                                DarkMode.Dark -> Res.string.settings_theme_dark
                            }
                        },
                        onSelect = { mode -> save { it.copy(darkMode = mode) } },
                    )
                }
                Setting(Res.string.settings_language, hint = Res.string.settings_language_restart) {
                    ChoiceGroup(
                        options = AppLanguage.entries,
                        selected = current.language,
                        label = {
                            when (it) {
                                AppLanguage.System -> Res.string.settings_system
                                AppLanguage.English -> Res.string.settings_language_english
                                AppLanguage.Dutch -> Res.string.settings_language_dutch
                            }
                        },
                        onSelect = { language -> save { it.copy(language = language) } },
                    )
                }
            }
        }
        StartupCard(settings, autostart)
        about?.let { AboutSection(it) }
    }
}

/** Settings → Start-up: start with Windows, hidden in the tray. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StartupCard(settings: SettingsStore, autostart: Autostart) {
    val scope = rememberCoroutineScope()
    var state by remember(autostart) { mutableStateOf<AutostartState?>(null) }
    LaunchedEffect(autostart) { state = withContext(Dispatchers.IO) { autostart.state() } }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            Text(stringResource(Res.string.settings_startup), style = MaterialTheme.typography.titleLargeEmphasized)
            Setting(
                Res.string.settings_start_with_windows,
                hint = when (state) {
                    AutostartState.Unavailable -> Res.string.settings_start_only_installed
                    AutostartState.DisabledInTaskManager -> Res.string.settings_start_disabled_in_task_manager
                    else -> Res.string.settings_start_hidden
                },
            ) {
                Switch(
                    checked = state == AutostartState.On,
                    enabled = state != null && state != AutostartState.Unavailable,
                    onCheckedChange = { on ->
                        settings.tryUpdate { it.copy(startWithWindows = on) }
                        scope.launch {
                            state = withContext(Dispatchers.IO) {
                                if (on) autostart.enable() else autostart.disable()
                                autostart.state()
                            }
                        }
                    },
                    modifier = Modifier.testTag(START_WITH_WINDOWS_TAG),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColourSetting(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val motion = MaterialTheme.motionScheme
    val source = if (mode is ThemeMode.Custom) ColourSource.Custom else ColourSource.Album
    Setting(
        Res.string.settings_colour,
        hint = if (source == ColourSource.Custom) Res.string.settings_colour_custom_hint else Res.string.settings_colour_album_hint,
    ) {
        ChoiceGroup(
            options = ColourSource.entries,
            selected = source,
            label = {
                when (it) {
                    ColourSource.Album -> Res.string.settings_colour_album
                    ColourSource.Custom -> Res.string.settings_colour_custom
                }
            },
            onSelect = { onChange(if (it == ColourSource.Album) ThemeMode.Album else ThemeMode.Custom(BrandSeed.toArgb())) },
        )
        AnimatedVisibility(
            visible = mode is ThemeMode.Custom,
            enter = motion.appear(),
            exit = motion.disappear(),
        ) {
            val selectedArgb = (mode as? ThemeMode.Custom)?.seedArgb
            SwatchRow(selectedArgb, onPick = { onChange(ThemeMode.Custom(it.toArgb())) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwatchRow(selectedArgb: Int?, onPick: (Color) -> Unit) {
    FlowRow(
        modifier = Modifier.padding(top = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        CustomSeedPresets.forEachIndexed { index, colour ->
            Swatch(
                colour = colour,
                selected = colour.toArgb() == selectedArgb,
                label = stringResource(Res.string.settings_swatch, index + 1),
                onClick = { if (colour.toArgb() != selectedArgb) onPick(colour) },
            )
        }
    }
}

/** A colour circle; when picked it morphs into a rounded square with a ring and a check. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Swatch(colour: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    val motion = MaterialTheme.motionScheme
    val corner by animateDpAsState(if (selected) 14.dp else SwatchSize / 2, motion.defaultSpatialSpec())
    val ring by animateDpAsState(if (selected) 3.dp else 0.dp, motion.fastSpatialSpec())
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier
            .size(SwatchSize)
            .clip(shape)
            .background(colour)
            .border(ring, MaterialTheme.colorScheme.onSurface, shape)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = selected,
            enter = scaleIn(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
            exit = scaleOut(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            Icon(
                AppIcons.Check,
                contentDescription = null,
                tint = if (colour.luminance() > 0.5f) Color.Black else Color.White,
            )
        }
    }
}

@Composable
private fun Setting(title: StringResource, hint: StringResource? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMediumEmphasized)
        content()
        if (hint != null) {
            Text(
                stringResource(hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Single choice as an Expressive connected button group; the checked button turns into a pill. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ChoiceGroup(options: List<T>, selected: T, label: (T) -> StringResource, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { if (option != selected) onSelect(option) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.semantics { role = Role.RadioButton },
            ) {
                Text(stringResource(label(option)))
            }
        }
    }
}
