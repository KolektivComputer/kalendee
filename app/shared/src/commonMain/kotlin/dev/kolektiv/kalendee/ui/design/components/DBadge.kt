package dev.kolektiv.kalendee.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens

enum class DBadgeSize(
    internal val height: Dp,
    internal val horizontalPadding: Dp,
    internal val textStyle: TextStyle,
) {
    Xs(20.dp, 6.dp, DType.xs),
    Sm(24.dp, 8.dp, DType.xs),
}

/** daisyUI `badge` colors, plus ghost/outline neutral styles. */
enum class DBadgeColor { Primary, Secondary, Accent, Success, Warning, Error, Ghost, Outline }

@Composable
fun DBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: DBadgeColor = DBadgeColor.Ghost,
    size: DBadgeSize = DBadgeSize.Sm,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    val shape = dimens.fieldShape
    val role = color.role
    val background = when (color) {
        DBadgeColor.Ghost -> colors.baseContent.copy(alpha = 0.1f)
        DBadgeColor.Outline -> Color.Transparent
        else -> role.fill(colors)
    }
    val contentColor = when (color) {
        DBadgeColor.Ghost, DBadgeColor.Outline -> colors.baseContent
        else -> role.content(colors)
    }
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = size.height)
            .clip(shape)
            .background(background)
            .then(
                if (color == DBadgeColor.Outline) {
                    Modifier.border(
                        width = dimens.border,
                        color = colors.baseContent.copy(alpha = 0.3f),
                        shape = shape,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = size.horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DText(
            text = text,
            style = size.textStyle,
            color = contentColor,
            maxLines = 1,
        )
    }
}

private val DBadgeColor.role: DColorRole
    get() = when (this) {
        DBadgeColor.Primary -> DColorRole.Primary
        DBadgeColor.Secondary -> DColorRole.Secondary
        DBadgeColor.Accent -> DColorRole.Accent
        DBadgeColor.Success -> DColorRole.Success
        DBadgeColor.Warning -> DColorRole.Warning
        DBadgeColor.Error -> DColorRole.Error
        DBadgeColor.Ghost, DBadgeColor.Outline -> DColorRole.Neutral
    }
