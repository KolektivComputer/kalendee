package dev.kolektiv.kalendee.ui.design.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens
import dev.kolektiv.kalendee.ui.icons.Lucide

/**
 * daisyUI `checkbox`: 20dp square with the field radius, primary fill when
 * checked, primary focus ring.
 */
@Composable
fun DCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalKalendeeColors.current
    val shape = LocalKalendeeDimens.current.fieldShape
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .size(20.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .clip(shape)
            .background(if (checked) colors.primary else colors.base100)
            .then(
                when {
                    focused && enabled -> Modifier.border(2.dp, colors.primary, shape)
                    !checked -> Modifier.border(1.5.dp, colors.baseContent.copy(alpha = 0.25f), shape)
                    else -> Modifier
                },
            )
            .hoverSurface(interactionSource, shape)
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            DIcon(icon = Lucide.Check, tint = colors.primaryContent, size = 14.dp)
        }
    }
}

/** daisyUI `radio`: 20dp circle, primary ring and dot when selected. */
@Composable
fun DRadio(
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalKalendeeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .size(20.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .clip(CircleShape)
            .background(if (selected) colors.primary else colors.base100)
            .then(
                when {
                    focused && enabled -> Modifier.border(2.dp, colors.primary, CircleShape)
                    !selected -> Modifier.border(1.5.dp, colors.baseContent.copy(alpha = 0.25f), CircleShape)
                    else -> Modifier
                },
            )
            .hoverSurface(interactionSource, CircleShape)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(colors.primaryContent),
            )
        }
    }
}

/** daisyUI `switch`: pill track, animated thumb, primary when on. */
@Composable
fun DSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalKalendeeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val trackWidth = 44.dp
    val trackHeight = 24.dp
    val thumbSize = 20.dp
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbSize - 4.dp else 0.dp,
        label = "dswitch-thumb",
    )
    Box(
        modifier = modifier
            .width(trackWidth)
            .height(trackHeight)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .clip(CircleShape)
            .background(if (checked) colors.primary else colors.base300)
            .then(
                if (focused && enabled) {
                    Modifier.border(2.dp, colors.primary, CircleShape)
                } else {
                    Modifier
                },
            )
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    ) {
        Box(
            modifier = Modifier
                .padding(2.dp)
                .offset(x = thumbOffset)
                .size(thumbSize)
                .clip(CircleShape)
                .background(if (checked) colors.primaryContent else colors.baseContent),
        )
    }
}
