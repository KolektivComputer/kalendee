package dev.kolektiv.kalendee.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens
import dev.kolektiv.kalendee.ui.icons.Lucide

/** Single-line daisyUI `input` with label above and error text below. */
@Composable
fun DTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    contentType: ContentType? = null,
    minHeight: Dp = LocalKalendeeDimens.current.input,
) {
    val semanticsType = contentType
    DFieldset(title = label, error = error, modifier = modifier) {
        DInputFrame(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            placeholder = placeholder,
            isError = !error.isNullOrBlank(),
            singleLine = singleLine,
            minLines = 1,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = VisualTransformation.None,
            minHeight = minHeight,
            semanticsModifier = if (semanticsType != null) {
                Modifier.semantics { this.contentType = semanticsType }
            } else {
                Modifier
            },
        )
    }
}

/**
 * Password input with a Lucide eye visibility toggle. The field carries
 * `password()` semantics while hidden.
 */
@Composable
fun DPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    var visible by remember { mutableStateOf(false) }
    val colors = LocalKalendeeColors.current
    DFieldset(title = label, error = error, modifier = modifier) {
        DInputFrame(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            placeholder = placeholder,
            isError = !error.isNullOrBlank(),
            singleLine = true,
            minLines = 1,
            maxLines = 1,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            minHeight = LocalKalendeeDimens.current.input,
            semanticsModifier = Modifier.semantics {
                contentType = ContentType.Password
                if (!visible) password()
            },
            trailing = {
                DIconButton(
                    icon = if (visible) Lucide.EyeOff else Lucide.Eye,
                    contentDescription = if (visible) "Hide password" else "Show password",
                    onClick = { visible = !visible },
                    enabled = enabled,
                    tint = colors.mutedContent,
                    size = 28.dp,
                    iconSize = 16.dp,
                )
            },
        )
    }
}

/** Multi-line daisyUI `textarea`. */
@Composable
fun DTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    minLines: Int = 4,
    maxLines: Int = 8,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    contentType: ContentType? = null,
) {
    val semanticsType = contentType
    DFieldset(title = label, error = error, modifier = modifier) {
        DInputFrame(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            placeholder = placeholder,
            isError = !error.isNullOrBlank(),
            singleLine = false,
            minLines = minLines,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = VisualTransformation.None,
            minHeight = LocalKalendeeDimens.current.input,
            semanticsModifier = if (semanticsType != null) {
                Modifier.semantics { this.contentType = semanticsType }
            } else {
                Modifier
            },
        )
    }
}

@Composable
private fun DInputFrame(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String?,
    isError: Boolean,
    singleLine: Boolean,
    minLines: Int,
    maxLines: Int,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    visualTransformation: VisualTransformation,
    minHeight: Dp,
    modifier: Modifier = Modifier,
    semanticsModifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val borderColor = when {
        isError -> colors.error
        focused && enabled -> colors.primary
        else -> colors.base300
    }
    val borderWidth = if (focused && enabled) 2.dp else dimens.border
    val shape = dimens.fieldShape
    val textColor = if (enabled) colors.baseContent else colors.baseContent.copy(alpha = 0.5f)

    CompositionLocalProvider(
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = colors.primary,
            backgroundColor = colors.primary.copy(alpha = 0.4f),
        ),
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .clip(shape)
                .background(colors.base100)
                .border(borderWidth, borderColor, shape)
                .then(semanticsModifier)
                .padding(horizontal = 12.dp),
            contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                textStyle = DType.base.copy(color = textColor),
                cursorBrush = SolidColor(colors.primary),
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                interactionSource = interactionSource,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = if (singleLine) 0.dp else 10.dp)
                    .padding(end = if (trailing != null) 32.dp else 0.dp),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty() && placeholder != null) {
                            DText(
                                text = placeholder,
                                style = DType.base,
                                color = colors.placeholder,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (trailing != null) {
                Box(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    content = { trailing() },
                )
            }
        }
    }
}
