package nl.diments.requestify

import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.app_name
import org.jetbrains.compose.resources.stringResource

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = stringResource(Res.string.app_name)) {
        Text(stringResource(Res.string.app_name))
    }
}
