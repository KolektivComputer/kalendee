package dev.kolektiv.kalendee.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens

/** 1px horizontal divider, `base-content/12` by default. */
@Composable
fun DDivider(
    modifier: Modifier = Modifier,
    color: Color = LocalKalendeeColors.current.baseContent.copy(alpha = 0.12f),
    thickness: Dp = LocalKalendeeDimens.current.border,
) {
    Box(modifier = modifier.fillMaxWidth().height(thickness).background(color))
}
