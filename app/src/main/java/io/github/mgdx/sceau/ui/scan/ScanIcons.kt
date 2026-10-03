/*
 * Tracés d'icônes issus de Material Icons (Google), https://github.com/google/material-design-icons,
 * distribués sous licence Apache 2.0 : https://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.mgdx.sceau.ui.scan

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Icônes propres à l'écran de scan, dessinées comme celles de `SceauIcons`. */
internal object ScanIcons {
    val FlashlightOn: ImageVector by lazy {
        icon(
            "FlashlightOn",
            "M6,2h12v3H6V2zM6,7v1l2,3v11h8V11l2,-3V7H6zM12,15.5c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 " +
                "1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z",
        )
    }

    val FlashlightOff: ImageVector by lazy {
        icon(
            "FlashlightOff",
            "M18,5V2H6v1.17L7.83,5zM16,11l2,-3V7H9.83L16,13.17zM2.81,2.81L1.39,4.22 8,10.83V22h8v-3.17l3.78,3.78 " +
                "1.41,-1.41z",
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
