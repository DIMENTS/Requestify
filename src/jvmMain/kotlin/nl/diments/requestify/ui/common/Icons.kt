package nl.diments.requestify.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few Material icons the app uses (filled, 24 dp grid). No icon library is on the classpath, and the
 * extended Material icons artifact is frozen at an old version, so the paths live here.
 */
object AppIcons {
    val PlayArrow by icon("PlayArrow", "M8,5v14l11,-7z")
    val Pause by icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")
    val SkipNext by icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    val Close by icon(
        "Close",
        "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z",
    )
    val Search by icon(
        "Search",
        "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16" +
            "c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5" +
            "S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
    )
    val DragIndicator by icon(
        "DragIndicator",
        "M11,18c0,1.1 -0.9,2 -2,2s-2,-0.9 -2,-2 0.9,-2 2,-2 2,0.9 2,2zM9,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2" +
            " -0.9,-2 -2,-2zM9,4c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM15,8c1.1,0 2,-0.9 2,-2" +
            "s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM15,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z" +
            "M15,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z",
    )
    val Bolt by icon(
        "Bolt",
        "M11,21h-1l1,-7H7.5c-0.58,0 -0.57,-0.32 -0.38,-0.66 0.19,-0.34 0.05,-0.08 0.07,-0.12C8.48,10.94 10.42,7.54 13,3" +
            "h1l-1,7h3.5c0.49,0 0.56,0.33 0.47,0.51l-0.07,0.15C12.96,17.55 11,21 11,21z",
    )
    val MusicNote by icon(
        "MusicNote",
        "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z",
    )
    val CheckCircle by icon(
        "CheckCircle",
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,17l-5,-5 1.41,-1.41L10,14.17" +
            "l7.59,-7.59L19,8l-9,9z",
    )
    val Warning by icon("Warning", "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z")
    val Sync by icon(
        "Sync",
        "M12,4V1L8,5l4,4V6c3.31,0 6,2.69 6,6 0,1.01 -0.25,1.97 -0.7,2.8l1.46,1.46C19.54,15.03 20,13.57 20,12" +
            "c0,-4.42 -3.58,-8 -8,-8zM12,18c-3.31,0 -6,-2.69 -6,-6 0,-1.01 0.25,-1.97 0.7,-2.8L5.24,7.74" +
            "C4.46,8.97 4,10.43 4,12c0,4.42 3.58,8 8,8v3l4,-4 -4,-4v3z",
    )
    val CloudOff by icon(
        "CloudOff",
        "M19.35,10.04C18.67,6.59 15.64,4 12,4c-1.48,0 -2.85,0.43 -4.01,1.17l1.46,1.46C10.21,6.23 11.08,6 12,6" +
            "c3.04,0 5.5,2.46 5.5,5.5v0.5H19c1.66,0 3,1.34 3,3 0,1.13 -0.64,2.11 -1.56,2.62l1.45,1.45" +
            "C23.16,18.16 24,16.68 24,15c0,-2.64 -2.05,-4.78 -4.65,-4.96zM3,5.27l2.75,2.74C2.56,8.15 0,10.77 0,14" +
            "c0,3.31 2.69,6 6,6h11.73l2,2L21,20.73 4.27,4 3,5.27zM7.73,10l8,8H6c-2.21,0 -4,-1.79 -4,-4" +
            "s1.79,-4 4,-4h1.73z",
    )

    private fun icon(name: String, path: String) = lazy {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
    }
}
