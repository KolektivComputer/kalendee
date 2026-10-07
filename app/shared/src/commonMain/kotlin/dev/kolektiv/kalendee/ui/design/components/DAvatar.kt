package dev.kolektiv.kalendee.ui.design.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.semibold

/** First letters of up to two words, uppercased ("ada lovelace" -> "AL"). */
fun avatarInitials(name: String): String =
    name.trim()
        .split(Regex("\\s+"))
        .asSequence()
        .mapNotNull { word -> word.firstOrNull()?.uppercaseChar() }
        .take(2)
        .joinToString("")

/**
 * Circular avatar. Without a [painter] the [initials] render on a
 * `base-content/20` circle; image loading is the caller's concern.
 */
@Composable
fun DAvatar(
    initials: String,
    modifier: Modifier = Modifier,
    painter: Painter? = null,
    size: Dp = 40.dp,
    background: Color = LocalKalendeeColors.current.baseContent.copy(alpha = 0.2f),
    contentColor: Color = LocalKalendeeColors.current.baseContent,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop,
            )
        } else {
            val style = if (size >= 48.dp) DType.base.semibold() else DType.sm.semibold()
            DText(
                text = avatarInitials(initials),
                style = style,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}
