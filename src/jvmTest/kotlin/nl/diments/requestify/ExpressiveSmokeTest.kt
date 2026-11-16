package nl.diments.requestify

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import nl.diments.requestify.resources.Res
import nl.diments.requestify.resources.app_name
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3ExpressiveApi::class)
class ExpressiveSmokeTest {
    @Test
    fun expressiveComponentsAndResourcesResolve() = runComposeUiTest {
        setContent {
            MaterialExpressiveTheme(motionScheme = MotionScheme.expressive()) {
                Column {
                    Text(stringResource(Res.string.app_name))
                    LinearWavyProgressIndicator(progress = { 0.5f })
                    ButtonGroup(overflowIndicator = {}) { clickableItem(onClick = {}, label = "Skip") }
                }
            }
        }
        onNodeWithText("Requestify").assertExists()
    }
}
