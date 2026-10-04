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

/** Paragraphe courant des écrans pédagogiques. */
@Composable
internal fun LearnParagraph(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
}

/**
 * Élément de liste précédé d'une puce dessinée (pas de caractère « • » codé en dur). La puce
 * est centrée sur la première ligne, quelle que soit la taille de police choisie.
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

/** Libellé technique (DG1, EF.SOD…) suivi de son explication. */
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
