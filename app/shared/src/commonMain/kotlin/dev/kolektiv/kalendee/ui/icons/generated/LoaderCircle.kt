// Generated from Lucide v1.52.0 (https://lucide.dev). Do not edit by hand.
// See licenses/lucide-ISC.txt and licenses/feather-MIT.txt at the repository root.

package dev.kolektiv.kalendee.ui.icons.generated

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val LoaderCircle: ImageVector
    get() {
        if (_LoaderCircle != null) {
            return _LoaderCircle!!
        }
        _LoaderCircle = ImageVector.Builder(
            name = "LoaderCircle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(21f, 12f)
                arcToRelative(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, -6.219f, -8.56f)
            }
        }.build()

        return _LoaderCircle!!
    }

@Suppress("ObjectPropertyName")
private var _LoaderCircle: ImageVector? = null
