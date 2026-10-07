package dev.kolektiv.kalendee.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens
import dev.kolektiv.kalendee.ui.icons.Lucide

data class DSelectOption(
    val value: String,
    val label: String,
    val enabled: Boolean = true,
)

data class DSelectGroup(
    val label: String? = null,
    val options: List<DSelectOption>,
)

/**
 * daisyUI `select` trigger that opens a dropdown menu anchored below it.
 * Options may be passed flat or as labelled [groups].
 */
@Composable
fun DSelect(
    selected: String?,
    options: List<DSelectOption>,
    onSelect: (DSelectOption) -> Unit,
    modifier: Modifier = Modifier,
    groups: List<DSelectGroup> = emptyList(),
    placeholder: String = "Select",
    label: String? = null,
    error: String? = null,
    enabled: Boolean = true,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    var expanded by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableIntStateOf(0) }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val allGroups = remember(options, groups) {
        buildList {
            if (options.isNotEmpty()) add(DSelectGroup(label = null, options = options))
            addAll(groups)
        }
    }
    val selectedLabel = allGroups.asSequence()
        .flatMap { it.options.asSequence() }
        .firstOrNull { it.value == selected }
        ?.label
    val borderColor = when {
        !error.isNullOrBlank() -> colors.error
        focused && enabled -> colors.primary
        else -> colors.base300
    }
    val shape = dimens.fieldShape
    val offsetPx = with(LocalDensity.current) { 4.dp.roundToPx() }

    DFieldset(title = label, error = error, modifier = modifier) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dimens.input)
                    .onSizeChanged { anchorWidth = it.width }
                    .clip(shape)
                    .background(colors.base100)
                    .border(if (focused && enabled) 2.dp else dimens.border, borderColor, shape)
                    .hoverSurface(interactionSource, shape)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled,
                        onClick = { expanded = !expanded },
                    )
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DText(
                    text = selectedLabel ?: placeholder,
                    style = DType.base,
                    color = if (selectedLabel != null) colors.baseContent else colors.placeholder,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DIcon(
                    icon = Lucide.ChevronDown,
                    tint = colors.mutedContent,
                    size = 16.dp,
                )
            }
            if (expanded) {
                Popup(
                    alignment = Alignment.BottomStart,
                    offset = IntOffset(0, offsetPx),
                    onDismissRequest = { expanded = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Column(
                        modifier = Modifier
                            .width(with(LocalDensity.current) { anchorWidth.toDp() })
                            .heightIn(max = 280.dp)
                            .clip(dimens.boxShape)
                            .background(colors.base100)
                            .border(dimens.border, colors.base300, dimens.boxShape)
                            .shadow(8.dp, dimens.boxShape)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                    ) {
                        allGroups.forEach { group ->
                            if (!group.label.isNullOrBlank()) {
                                DText(
                                    text = group.label.uppercase(),
                                    style = DType.sectionLabel,
                                    color = colors.mutedContent,
                                    modifier = Modifier.padding(
                                        start = 12.dp,
                                        end = 12.dp,
                                        top = 8.dp,
                                        bottom = 4.dp,
                                    ),
                                )
                            }
                            group.options.forEach { option ->
                                DSelectOptionRow(
                                    option = option,
                                    selected = option.value == selected,
                                    onSelect = {
                                        onSelect(option)
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DSelectOptionRow(
    option: DSelectOption,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 36.dp)
            .hoverSurface(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = option.enabled,
                onClick = onSelect,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DText(
            text = option.label,
            style = DType.sm,
            color = if (option.enabled) colors.baseContent else colors.mutedContent,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            DIcon(
                icon = Lucide.Check,
                tint = colors.primary,
                size = 16.dp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
