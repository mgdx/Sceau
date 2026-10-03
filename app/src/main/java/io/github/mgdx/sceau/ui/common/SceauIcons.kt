/*
 * Tracés d'icônes issus de Material Icons (Google), https://github.com/google/material-design-icons,
 * distribués sous licence Apache 2.0 : https://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.mgdx.sceau.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Icônes Material redessinées ici en [ImageVector] à partir de leur tracé : Material 3
 * n'embarque plus `material-icons-core`, et une dépendance de plus ne se justifie pas pour
 * une quinzaine de tracés. Partagées par tous les écrans.
 */
object SceauIcons {
    val ArrowBack: ImageVector by lazy { icon("ArrowBack", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z") }

    val CheckCircle: ImageVector by lazy {
        icon(
            "CheckCircle",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z",
        )
    }

    val Cancel: ImageVector by lazy {
        icon(
            "Cancel",
            "M12,2C6.47,2 2,6.47 2,12s4.47,10 10,10 10,-4.47 10,-10S17.53,2 12,2zM17,15.59L15.59,17 12,13.41 8.41,17 7,15.59 " +
                "10.59,12 7,8.41 8.41,7 12,10.59 15.59,7 17,8.41 13.41,12 17,15.59z",
        )
    }

    val Warning: ImageVector by lazy { icon("Warning", "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z") }

    val Help: ImageVector by lazy {
        icon(
            "Help",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,19h-2v-2h2v2zM15.07,11.25l-0.9,0.92C13.45,12.9 " +
                "13,13.5 13,15h-2v-0.5c0,-1.1 0.45,-2.1 1.17,-2.83l1.24,-1.26c0.37,-0.36 0.59,-0.86 0.59,-1.41 " +
                "0,-1.1 -0.9,-2 -2,-2s-2,0.9 " +
                "-2,2H8c0,-2.21 1.79,-4 4,-4s4,1.79 4,4c0,0.88 -0.36,1.68 -0.93,2.25z",
        )
    }

    val RemoveCircle: ImageVector by lazy {
        icon("RemoveCircle", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM17,13H7v-2h10v2z")
    }

    val Delete: ImageVector by lazy {
        icon("Delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z")
    }

    val ExpandMore: ImageVector by lazy { icon("ExpandMore", "M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z") }

    val ExpandLess: ImageVector by lazy { icon("ExpandLess", "M12,8l-6,6 1.41,1.41L12,10.83l4.59,4.58L18,14z") }

    val Fullscreen: ImageVector by lazy {
        icon("Fullscreen", "M7,14H5v5h5v-2H7v-3zM5,10h2V7h3V5H5v5zM17,17h-3v2h5v-5h-2v3zM14,5v2h3v3h2V5h-5z")
    }

    val Person: ImageVector by lazy {
        icon(
            "Person",
            "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z",
        )
    }

    val Close: ImageVector by lazy {
        icon(
            "Close",
            "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z",
        )
    }

    val Nfc: ImageVector by lazy {
        icon(
            "Nfc",
            "M20,2L4,2c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2L22,4c0,-1.1 -0.9,-2 -2,-2z" +
                "M20,20L4,20L4,4h16v16zM18,6h-5c-1.1,0 -2,0.9 -2,2v2.28c-0.6,0.35 -1,0.98 -1,1.72 0,1.1 0.9,2 2,2" +
                "s2,-0.9 2,-2c0,-0.74 -0.4,-1.38 -1,-1.72L13,8h3v8L8,16L8,8h2L10,6L6,6v12h12L18,6z",
        )
    }

    val Check: ImageVector by lazy {
        icon("Check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
    }

    val Error: ImageVector by lazy {
        icon(
            "Error",
            "M11,15h2v2h-2zM11,7h2v6h-2zM11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12" +
                "S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8z",
        )
    }

    val MoreVert: ImageVector by lazy {
        icon(
            "MoreVert",
            "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 " +
                "2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z",
        )
    }

    val Calendar: ImageVector by lazy {
        icon(
            "Calendar",
            "M19,4h-1V2h-2v2H8V2H6v2H5C3.89,4 3.01,4.9 3.01,6L3,20c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V6" +
                "C21,4.9 20.1,4 19,4zM19,20H5V10h14V20zM19,8H5V6h14V8z",
        )
    }

    val PhotoCamera: ImageVector by lazy {
        icon(
            "PhotoCamera",
            "M12,12m-3.2,0a3.2,3.2 0,1 1,6.4 0a3.2,3.2 0,1 1,-6.4 0M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16" +
                "c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z",
        )
    }

    private fun icon(
        name: String,
        pathData: String,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
            .build()
}
