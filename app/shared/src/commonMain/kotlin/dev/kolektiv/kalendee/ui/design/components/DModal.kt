package dev.kolektiv.kalendee.ui.design.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens

/**
 * Centered dialog: base-100 card, box radius, black/60 scrim, 480dp max width,
 * fade/scale-in. Clicking the scrim or pressing back dismisses.
 */
@Composable
fun DModal(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 480.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "dmodal-progress",
    )
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.scrim.copy(alpha = colors.scrim.alpha * progress))
                .pointerInput(Unit) { detectTapGestures { onDismissRequest() } },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = modifier
                    .padding(24.dp)
                    .widthIn(max = maxWidth)
                    .fillMaxWidth(0.92f)
                    .graphicsLayer {
                        alpha = progress
                        scaleX = 0.96f + 0.04f * progress
                        scaleY = 0.96f + 0.04f * progress
                    }
                    .clip(dimens.boxShape)
                    .background(colors.base100)
                    .border(dimens.border, colors.base300, dimens.boxShape)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(dimens.space6),
                content = content,
            )
        }
    }
}
