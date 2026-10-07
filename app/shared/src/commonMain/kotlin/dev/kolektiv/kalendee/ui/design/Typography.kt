package dev.kolektiv.kalendee.ui.design

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * Type scale used on the web (`text-xs`..`text-3xl`), backed by the system
 * font stack (Compose's default [androidx.compose.ui.text.font.FontFamily]).
 */
object DType {
    val xs: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val sm: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val base: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    val lg: TextStyle = TextStyle(fontSize = 18.sp, lineHeight = 28.sp)
    val xl: TextStyle = TextStyle(fontSize = 20.sp, lineHeight = 28.sp)
    val xl2: TextStyle = TextStyle(fontSize = 24.sp, lineHeight = 32.sp)
    val xl3: TextStyle = TextStyle(fontSize = 30.sp, lineHeight = 36.sp)

    /** Uppercase section label: 12sp semibold with tracking. */
    val sectionLabel: TextStyle = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.24.sp,
    )
}

fun TextStyle.medium(): TextStyle = copy(fontWeight = FontWeight.Medium)

fun TextStyle.semibold(): TextStyle = copy(fontWeight = FontWeight.SemiBold)

fun TextStyle.bold(): TextStyle = copy(fontWeight = FontWeight.Bold)

/**
 * TextStyle inherited by [DText] inside container components (buttons, menus,
 * list items). Null means "not provided".
 */
val LocalDTextStyle = staticCompositionLocalOf<TextStyle?> { null }

/** No-material text. Inherits button/list content color and [LocalDTextStyle]. */
@Composable
fun DText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
    color: Color = LocalDContentColor.current.takeOrElse { LocalKalendeeColors.current.baseContent },
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val resolvedStyle = style ?: LocalDTextStyle.current ?: DType.base
    BasicText(
        text = text,
        modifier = modifier,
        style = resolvedStyle.merge(TextStyle(color = color, textAlign = textAlign ?: TextAlign.Unspecified)),
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
    )
}

/** [AnnotatedString] overload of [DText]. */
@Composable
fun DText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
    color: Color = LocalDContentColor.current.takeOrElse { LocalKalendeeColors.current.baseContent },
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val resolvedStyle = style ?: LocalDTextStyle.current ?: DType.base
    BasicText(
        text = text,
        modifier = modifier,
        style = resolvedStyle.merge(TextStyle(color = color, textAlign = textAlign ?: TextAlign.Unspecified)),
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
    )
}
