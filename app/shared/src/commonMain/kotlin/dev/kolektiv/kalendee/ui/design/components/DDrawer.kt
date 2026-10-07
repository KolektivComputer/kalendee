package dev.kolektiv.kalendee.ui.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens
import kotlinx.coroutines.delay

private const val DrawerAnimationMillis = 200

/**
 * Left side drawer (280dp by default): base-100 panel with a right border and
 * scrim, sliding in from the left. [open] drives both animations; the dialog
 * stays composed until the exit animation finished.
 */
@Composable
fun DDrawer(
    open: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 280.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    var rendered by remember { mutableStateOf(open) }
    LaunchedEffect(open) {
        if (open) {
            rendered = true
        } else if (rendered) {
            delay(DrawerAnimationMillis.toLong())
            rendered = false
        }
    }
    if (!rendered) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = open,
                enter = fadeIn(tween(DrawerAnimationMillis)),
                exit = fadeOut(tween(DrawerAnimationMillis)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.scrim)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                )
            }
            AnimatedVisibility(
                visible = open,
                enter = slideInHorizontally(tween(DrawerAnimationMillis + 20)) { width -> -width },
                exit = slideOutHorizontally(tween(DrawerAnimationMillis)) { width -> -width },
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Column(
                    modifier = modifier
                        .fillMaxHeight()
                        .width(width)
                        .background(colors.base100)
                        .drawBehind {
                            val stroke = dimens.border.toPx()
                            drawLine(
                                color = colors.base300,
                                start = Offset(size.width - stroke / 2f, 0f),
                                end = Offset(size.width - stroke / 2f, size.height),
                                strokeWidth = stroke,
                            )
                        },
                    content = content,
                )
            }
        }
    }
}
