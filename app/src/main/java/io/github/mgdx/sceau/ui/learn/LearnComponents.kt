package io.github.mgdx.sceau.ui.learn

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Body paragraph of the learning screens. */
@Composable
internal fun LearnParagraph(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
}

/**
 * List item preceded by a drawn bullet (no hard-coded "•" character). The bullet is
 * centered on the first line, whatever the chosen font size.
 */
@Composable
internal fun LearnBullet(text: String) {
    val style = MaterialTheme.typography.bodyLarge
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = (lineHeight - BULLET_SIZE) / 2)) {
            Box(
                Modifier
                    .size(BULLET_SIZE)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary),
            )
        }
        Text(text, style = style)
    }
}

/** Technical label (DG1, EF.SOD...) followed by its explanation. */
@Composable
internal fun LearnFileRow(
    label: String,
    description: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(description, style = MaterialTheme.typography.bodyMedium)
    }
}

private val BULLET_SIZE = 6.dp
