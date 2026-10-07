package dev.kolektiv.kalendee.ui.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SheetAnimationMillis = 200
private const val DismissVelocity = 1500f

/**
 * Bottom sheet: full-width base-100 panel with top box-radius corners and a
 * drag handle. Slides in from the bottom; drag down (or fling) and tapping the
 * scrim dismiss with a slide-out.
 */
@Composable
fun DSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val dragOffset = remember { Animatable(0f) }
    val draggableState = rememberDraggableState { delta ->
        scope.launch {
            dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f))
        }
    }
    val dismissThresholdPx = with(density) { 120.dp.toPx() }
    fun dismiss() {
        if (dismissing) return
        dismissing = true
        visible = false
        scope.launch {
            delay(SheetAnimationMillis.toLong())
            onDismissRequest()
        }
    }

    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(SheetAnimationMillis)),
                exit = fadeOut(tween(SheetAnimationMillis)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.scrim)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { dismiss() },
                        ),
                )
            }
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(tween(SheetAnimationMillis + 20)) { height -> height },
                exit = slideOutVertically(tween(SheetAnimationMillis)) { height -> height },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    modifier = modifier
                        .fillMaxWidth()
                        .offset { IntOffset(0, dragOffset.value.roundToInt().coerceAtLeast(0)) }
                        .clip(
                            RoundedCornerShape(
                                topStart = dimens.radiusBox,
                                topEnd = dimens.radiusBox,
                            ),
                        )
                        .background(colors.base100)
                        .drawBehind {
                            val stroke = dimens.border.toPx()
                            drawLine(
                                color = colors.base300,
                                start = Offset(0f, stroke / 2f),
                                end = Offset(size.width, stroke / 2f),
                                strokeWidth = stroke,
                            )
                        },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .draggable(
                                state = draggableState,
                                orientation = Orientation.Vertical,
                                onDragStopped = { velocity ->
                                    if (dragOffset.value > dismissThresholdPx || velocity > DismissVelocity) {
                                        dismiss()
                                    } else {
                                        dragOffset.animateTo(0f)
                                    }
                                },
                            )
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(colors.baseContent.copy(alpha = 0.3f)),
                        )
                    }
                    Column(
                        modifier = Modifier.padding(
                            start = dimens.space4,
                            end = dimens.space4,
                            bottom = dimens.space4,
                        ),
                        content = content,
                    )
                }
            }
        }
    }
}
