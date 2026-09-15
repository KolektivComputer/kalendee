package dev.kolektiv.kalendee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SECTIONS = listOf(
    "User settings" to listOf("profile", "appearance", "notifications"),
    "Calendars" to listOf("calendars", "connections", "sync", "holidays"),
)

private val LABELS = mapOf(
    "profile" to "My Profile",
    "appearance" to "Appearance",
    "notifications" to "Notifications",
    "calendars" to "Calendars",
    "connections" to "Connected Accounts",
    "sync" to "Devices & sync",
    "holidays" to "Holidays",
)

@Composable
fun SettingsOverlay(
    open: Boolean,
    section: String,
    viewerName: String,
    calendars: List<CalendarUi>,
    accent: String,
    onSection: (String) -> Unit,
    onAccent: (String) -> Unit,
    onDismiss: () -> Unit,
    onSignIn: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    signedIn: Boolean = false,
    localOnly: Boolean = true,
) {
    if (!open) return
    val colors = KalendeeTheme.colors
    Row(Modifier.fillMaxSize().background(colors.base100)) {
        Column(
            modifier = Modifier
                .width(238.dp)
                .fillMaxHeight()
                .padding(start = 20.dp, end = 6.dp, top = 60.dp, bottom = 60.dp),
        ) {
            SECTIONS.forEach { (group, items) ->
                BasicText(
                    group.uppercase(),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = TextStyle(
                        color = colors.baseContent.copy(alpha = 0.48f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp,
                    ),
                )
                items.forEach { id ->
                    val active = id == section
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (active) colors.baseContent.copy(alpha = 0.16f) else colors.base100)
                            .clickable { onSection(id) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        BasicText(
                            LABELS[id] ?: id,
                            style = TextStyle(
                                color = colors.baseContent.copy(alpha = if (active) 1f else 0.78f),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 40.dp, top = 60.dp, end = 24.dp, bottom = 80.dp),
        ) {
            BasicText(
                LABELS[section] ?: section,
                modifier = Modifier.padding(bottom = 20.dp),
                style = TextStyle(color = colors.baseContent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            )
            when (section) {
                "profile" -> ProfileCard(viewerName, signedIn, onSignIn)
                "appearance" -> AccentPicker(accent, onAccent)
                "notifications" -> NotifyCard()
                "calendars" -> CalendarList(calendars)
                "connections" -> ConnectionsCard(signedIn)
                "sync" -> SyncCard(signedIn = signedIn, localOnly = localOnly, onSignIn = onSignIn, onSyncNow = onSyncNow)
                else -> BasicText(
                    "Holiday calendars land here as a virtual calendar colored secondary.",
                    style = TextStyle(color = colors.baseContent.copy(alpha = 0.6f), fontSize = 14.sp),
                )
            }
        }
        Column(
            modifier = Modifier.width(60.dp).padding(top = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(2.dp, colors.baseContent.copy(alpha = 0.45f), CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("×", style = TextStyle(color = colors.baseContent.copy(alpha = 0.7f), fontSize = 18.sp))
            }
            BasicText(
                "ESC",
                modifier = Modifier.padding(top = 8.dp),
                style = TextStyle(
                    color = colors.baseContent.copy(alpha = 0.4f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                ),
            )
        }
    }
}

@Composable
private fun ProfileCard(viewerName: String, signedIn: Boolean, onSignIn: () -> Unit) {
    val colors = KalendeeTheme.colors
    Column(Modifier.clip(RoundedCornerShape(8.dp)).background(colors.base200)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .border(6.dp, colors.base200, CircleShape)
                    .background(colors.primary),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    viewerName.take(1).ifBlank { "K" },
                    style = TextStyle(color = colors.primaryContent, fontSize = 24.sp, fontWeight = FontWeight.Bold),
                )
            }
            Column {
                BasicText(viewerName, style = TextStyle(color = colors.baseContent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
                BasicText(
                    if (signedIn) "Signed in" else "Working locally",
                    style = TextStyle(color = colors.baseContent.copy(alpha = 0.5f), fontSize = 13.sp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.base300))
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Display name", style = TextStyle(color = colors.baseContent, fontSize = 14.sp))
            Spacer(Modifier.weight(1f))
            BasicText(viewerName, style = TextStyle(color = colors.baseContent.copy(alpha = 0.6f), fontSize = 14.sp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.base300))
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Time zone", style = TextStyle(color = colors.baseContent, fontSize = 14.sp))
            Spacer(Modifier.weight(1f))
            BasicText("America/Chicago", style = TextStyle(color = colors.baseContent.copy(alpha = 0.6f), fontSize = 14.sp))
        }
        if (!signedIn) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.base300))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable(onClick = onSignIn)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("Sign in to a Kalendee server", style = TextStyle(color = colors.primary, fontSize = 14.sp))
            }
        }
    }
}

@Composable
private fun AccentPicker(accent: String, onAccent: (String) -> Unit) {
    val colors = KalendeeTheme.colors
    val options = listOf("primary", "secondary", "accent", "info", "success", "error")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { id ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.base200)
                    .then(if (accent == id) Modifier.border(2.dp, colors.primary, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable { onAccent(id) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(colors.named(id)))
                BasicText(id.replaceFirstChar { it.titlecase() }, style = TextStyle(color = colors.baseContent, fontSize = 14.sp))
            }
        }
    }
}

@Composable
private fun NotifyCard() {
    val colors = KalendeeTheme.colors
    Column(Modifier.clip(RoundedCornerShape(8.dp)).background(colors.base200)) {
        listOf("At event start", "15 minutes before", "Browser notifications").forEachIndexed { i, row ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.base300))
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(row, style = TextStyle(color = colors.baseContent, fontSize = 14.sp))
            }
        }
    }
}

@Composable
private fun CalendarList(calendars: List<CalendarUi>) {
    val colors = KalendeeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        calendars.forEach { calendar ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.base200)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(colors.named(calendar.color)))
                BasicText(calendar.name, modifier = Modifier.weight(1f), style = TextStyle(color = colors.baseContent, fontSize = 14.sp))
            }
        }
    }
}

@Composable
private fun ConnectionsCard(signedIn: Boolean) {
    val colors = KalendeeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText(
            "Google and Discord connections live on the Kalendee server. Sign in, then open Connected Accounts on the web to link them. Sync now pulls mirrored calendars into this device.",
            style = TextStyle(color = colors.baseContent.copy(alpha = 0.6f), fontSize = 14.sp),
        )
        BasicText(
            if (signedIn) "Signed in — connect Google from Settings → Connected Accounts in the browser, then Sync now here."
            else "Working locally. Sign in to a server to mirror Google Calendar.",
            style = TextStyle(color = colors.baseContent.copy(alpha = 0.72f), fontSize = 14.sp),
        )
    }
}

@Composable
private fun SyncCard(
    signedIn: Boolean,
    localOnly: Boolean,
    onSignIn: () -> Unit,
    onSyncNow: () -> Unit,
) {
    val colors = KalendeeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText(
            if (!signedIn || localOnly) "This device only — events stay on the device."
            else "Account sync on — this week is shared with web and other signed-in devices.",
            style = TextStyle(color = colors.baseContent.copy(alpha = 0.7f), fontSize = 14.sp),
        )
        if (!signedIn) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.base200)
                    .clickable(onClick = onSignIn)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                BasicText("Sign in to a Kalendee server", style = TextStyle(color = colors.primary, fontSize = 14.sp))
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.primary)
                    .clickable(onClick = onSyncNow)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                BasicText("Sync now", style = TextStyle(color = colors.primaryContent, fontSize = 14.sp))
            }
        }
    }
}
