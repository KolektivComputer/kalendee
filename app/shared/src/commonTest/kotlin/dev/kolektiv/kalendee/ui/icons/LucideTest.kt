package dev.kolektiv.kalendee.ui.icons

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LucideTest {

    @Test
    fun allIconsBuildAt24Dp() {
        val icons = listOf(
            Lucide.AlertCircle,
            Lucide.ArrowLeft,
            Lucide.ArrowRight,
            Lucide.Bell,
            Lucide.BellRing,
            Lucide.Building2,
            Lucide.Calendar,
            Lucide.CalendarDays,
            Lucide.CalendarPlus,
            Lucide.CalendarRange,
            Lucide.Check,
            Lucide.ChevronDown,
            Lucide.ChevronLeft,
            Lucide.ChevronRight,
            Lucide.ChevronUp,
            Lucide.Circle,
            Lucide.Clock,
            Lucide.Ellipsis,
            Lucide.Eye,
            Lucide.EyeOff,
            Lucide.ExternalLink,
            Lucide.Globe,
            Lucide.LayoutGrid,
            Lucide.Link,
            Lucide.List,
            Lucide.LoaderCircle,
            Lucide.Lock,
            Lucide.LogIn,
            Lucide.LogOut,
            Lucide.Mail,
            Lucide.MapPin,
            Lucide.Menu,
            Lucide.Monitor,
            Lucide.Moon,
            Lucide.MoveLeft,
            Lucide.MoveRight,
            Lucide.Palette,
            Lucide.Pencil,
            Lucide.Plus,
            Lucide.RefreshCw,
            Lucide.Search,
            Lucide.Settings,
            Lucide.SlidersHorizontal,
            Lucide.Sun,
            Lucide.Trash2,
            Lucide.User,
            Lucide.UserPlus,
            Lucide.Users,
            Lucide.WifiOff,
            Lucide.X,
        )

        assertEquals(50, icons.size)
        icons.forEach { icon ->
            assertTrue(icon.root.size > 0, "${icon.name} has no path nodes")
            assertEquals(24.dp, icon.defaultWidth, icon.name)
            assertEquals(24.dp, icon.defaultHeight, icon.name)
        }
    }
}
