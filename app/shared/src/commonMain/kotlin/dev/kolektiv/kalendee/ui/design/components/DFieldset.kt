package dev.kolektiv.kalendee.ui.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.semibold

/** daisyUI `fieldset`: legend above the control, error text below. */
@Composable
fun DFieldset(
    title: String? = null,
    error: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalKalendeeColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            DText(
                text = title,
                style = DType.sm.semibold(),
                color = colors.subtleContent,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        content()
        if (!error.isNullOrBlank()) {
            DText(
                text = error,
                style = DType.xs,
                color = colors.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
