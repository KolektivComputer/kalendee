package dev.kolektiv.kalendee.ui.design.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.ui.design.LocalDContentColor
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors

/** Tinted lucide icon. Inherits the container content color when [tint] is unspecified. */
@Composable
fun DIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = LocalDContentColor.current.takeOrElse { LocalKalendeeColors.current.baseContent },
    size: Dp = 20.dp,
    contentDescription: String? = null,
) {
    Image(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}
