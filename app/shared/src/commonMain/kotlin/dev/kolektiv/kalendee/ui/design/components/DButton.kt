package dev.kolektiv.kalendee.ui.design.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalDContentColor
import dev.kolektiv.kalendee.ui.design.LocalDTextStyle
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens
import dev.kolektiv.kalendee.ui.design.medium

/** daisyUI `btn-*` variants. */
enum class DButtonVariant { Primary, Secondary, Accent, Neutral, Info, Success, Warning, Error, Ghost }

/** daisyUI button sizes: `btn-xs` 24dp, `btn-sm` 32dp, `btn-md` 40dp. */
enum class DButtonSize(
    internal val height: Dp,
    internal val horizontalPadding: Dp,
    internal val textStyle: TextStyle,
    internal val iconSize: Dp,
) {
    Xs(24.dp, 8.dp, DType.xs.medium(), 14.dp),
    Sm(32.dp, 12.dp, DType.sm.medium(), 16.dp),
    Md(40.dp, 16.dp, DType.sm.medium(), 18.dp),
}

/** daisyUI button shapes: default (field radius), `btn-square`, `btn-circle`. */
enum class DButtonShape { Default, Square, Circle }

@Composable
fun DButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: DButtonVariant = DButtonVariant.Primary,
    size: DButtonSize = DButtonSize.Md,
    shape: DButtonShape = DButtonShape.Default,
    loading: Boolean = false,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val active = enabled && !loading
    val role = variant.role
    val fill = when {
        variant == DButtonVariant.Ghost -> Color.Transparent
        active -> role.fill(colors)
        else -> role.fill(colors).copy(alpha = 0.5f)
    }
    val contentColor = when {
        variant == DButtonVariant.Ghost -> colors.baseContent
        active -> role.content(colors)
        else -> role.content(colors).copy(alpha = 0.5f)
    }
    val shapeValue = when (shape) {
        DButtonShape.Default -> dimens.fieldShape
        DButtonShape.Square -> RectangleShape
        DButtonShape.Circle -> CircleShape
    }
    val scale by animateFloatAsState(
        targetValue = if (pressed && active) 0.97f else 1f,
        label = "dbutton-scale",
    )
    val sizing = if (shape == DButtonShape.Circle) Modifier.size(size.height) else Modifier.height(size.height)
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(sizing)
            .clip(shapeValue)
            .background(fill)
            .hoverSurface(interactionSource, shapeValue)
            .then(
                if (focused && active) {
                    Modifier.border(2.dp, colors.primary, shapeValue)
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = active,
                onClick = onClick,
            )
            .padding(horizontal = if (shape == DButtonShape.Circle) 0.dp else size.horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(
            LocalDContentColor provides contentColor,
            LocalDTextStyle provides size.textStyle,
        ) {
            if (loading) {
                DSpinner(size = size.iconSize, color = contentColor)
            } else {
                content()
            }
        }
    }
}

/** Round ghost icon button with hover/pressed overlays and a press scale. */
@Composable
fun DIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalDContentColor.current.takeOrElse { LocalKalendeeColors.current.baseContent },
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.92f else 1f,
        label = "diconbutton-scale",
    )
    val resolvedTint = if (enabled) tint else tint.copy(alpha = 0.5f)
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(size)
            .clip(CircleShape)
            .hoverSurface(interactionSource, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        DIcon(
            icon = icon,
            tint = resolvedTint,
            size = iconSize,
            contentDescription = contentDescription,
        )
    }
}

private val DButtonVariant.role: DColorRole
    get() = when (this) {
        DButtonVariant.Primary -> DColorRole.Primary
        DButtonVariant.Secondary -> DColorRole.Secondary
        DButtonVariant.Accent -> DColorRole.Accent
        DButtonVariant.Neutral -> DColorRole.Neutral
        DButtonVariant.Info -> DColorRole.Info
        DButtonVariant.Success -> DColorRole.Success
        DButtonVariant.Warning -> DColorRole.Warning
        DButtonVariant.Error -> DColorRole.Error
        DButtonVariant.Ghost -> DColorRole.Primary
    }
