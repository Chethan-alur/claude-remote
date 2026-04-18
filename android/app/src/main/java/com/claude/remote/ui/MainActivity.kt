package com.claude.remote.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.claude.remote.service.SessionService

/**
 * Hosts the Compose UI and binds to [SessionService].
 *
 * TODO(claude-code):
 *   - Wire navigation between Sessions / Terminal / Pairing screens
 *   - Pass a real ViewModel that exposes [SessionService.messages] as state
 */
class MainActivity : ComponentActivity() {

    private var service: SessionService? = null

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored — UI will show a hint if denied */ }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as SessionService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureNotificationPermission()
        startAndBindService()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SessionsScreen(
                        // TODO(claude-code): plumb real data
                        sessions = emptyList(),
                        onNewSession = { /* TODO */ },
                        onOpen = { /* TODO: navigate to TerminalScreen */ },
                    )
                }
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, SessionService::class.java).apply {
            action = SessionService.ACTION_CONNECT
        }
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        unbindService(conn)
        super.onDestroy()
    }
}
