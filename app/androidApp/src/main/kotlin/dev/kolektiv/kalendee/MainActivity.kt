package dev.kolektiv.kalendee

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.kolektiv.kalendee.client.SessionStore
import dev.kolektiv.kalendee.notify.AndroidReminders

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        SessionStore.appContext = applicationContext
        AndroidReminders.appContext = applicationContext
        ensureReminderChannel()
        setContent {
            App()
        }
    }

    private fun ensureReminderChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                AndroidReminders.ChannelId,
                AndroidReminders.ChannelName,
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
