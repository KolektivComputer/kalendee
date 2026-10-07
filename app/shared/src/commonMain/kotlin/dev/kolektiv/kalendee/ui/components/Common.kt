package dev.kolektiv.kalendee.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.client.KalendeeApiException
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens
import dev.kolektiv.kalendee.ui.design.components.DAlert
import dev.kolektiv.kalendee.ui.design.components.DAlertColor
import dev.kolektiv.kalendee.ui.design.components.DIconButton
import dev.kolektiv.kalendee.ui.design.semibold
import dev.kolektiv.kalendee.ui.icons.Lucide

/** Phone-first scrollable column capped at 560dp and centered on wide screens. */
@Composable
fun PageColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .then(modifier),
            content = content,
        )
    }
}

/** Title row with optional in-app back button and trailing actions. */
@Composable
fun ScreenTitle(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = dimens.space3)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            if (onBack != null) {
                DIconButton(
                    icon = Lucide.ArrowLeft,
                    contentDescription = "Back",
                    onClick = onBack,
                    size = 36.dp,
                    iconSize = 20.dp,
                    modifier = Modifier.padding(end = dimens.space2),
                )
            }
            DText(
                text = title,
                style = DType.xl2.semibold(),
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            actions()
        }
        if (!subtitle.isNullOrBlank()) {
            DText(
                text = subtitle,
                style = DType.sm,
                color = colors.mutedContent,
                modifier = Modifier.padding(top = dimens.space1),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SectionTitle(title: String) {
    val dimens = LocalKalendeeDimens.current
    DText(
        text = title,
        style = DType.lg.semibold(),
        modifier = Modifier.padding(top = dimens.space4, bottom = dimens.space2),
    )
}

@Composable
fun InlineError(message: String?, modifier: Modifier = Modifier) {
    if (message.isNullOrBlank()) return
    val colors = LocalKalendeeColors.current
    DText(
        text = message,
        style = DType.sm,
        color = colors.error,
        modifier = modifier.padding(top = LocalKalendeeDimens.current.space2),
    )
}

@Composable
fun Notice(
    text: String?,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (text.isNullOrBlank()) return
    val colors = LocalKalendeeColors.current
    DAlert(
        color = DAlertColor.Info,
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
        icon = Lucide.AlertCircle,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DText(
                text = text,
                style = DType.sm,
                color = colors.baseContent,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                DIconButton(
                    icon = Lucide.X,
                    contentDescription = "Dismiss",
                    onClick = onDismiss,
                    tint = colors.baseContent,
                    size = 40.dp,
                    iconSize = 16.dp,
                )
            }
        }
    }
}

@Composable
fun VerticalSpacer(height: Int = 8) {
    Box(modifier = Modifier.fillMaxWidth().height(height.dp))
}

/** Human-readable message for anything thrown by an AppState action. */
fun messageOf(error: Throwable): String = when (error) {
    is KalendeeApiException -> error.message
    else -> error.message?.takeIf { it.isNotBlank() }
        ?: error::class.simpleName
        ?: "Something went wrong"
}
