package com.claude.remote.data

import android.content.Context
import com.claude.remote.BuildConfig

/**
 * Tiny persistent settings store (SharedPreferences — no extra dependency).
 *
 * Holds the daemon address the app connects to and the device token. The
 * daemon address is user-editable in the app so it can be pointed at a LAN IP,
 * a VPN/WireGuard address, or the emulator alias without rebuilding.
 */
class Settings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("claude_remote", Context.MODE_PRIVATE)

    var daemonHost: String
        get() = prefs.getString(KEY_HOST, null) ?: BuildConfig.DEFAULT_DAEMON_HOST
        set(v) = prefs.edit().putString(KEY_HOST, v.trim()).apply()

    var daemonPort: Int
        get() = prefs.getInt(KEY_PORT, BuildConfig.DEFAULT_DAEMON_PORT)
        set(v) = prefs.edit().putInt(KEY_PORT, v).apply()

    /** Token sent in `hello`. Until pairing is wired, a placeholder the daemon
     *  accepts when started without --require-auth. */
    var token: String
        get() = prefs.getString(KEY_TOKEN, null) ?: "dev_placeholder"
        set(v) = prefs.edit().putString(KEY_TOKEN, v.trim()).apply()

    private companion object {
        const val KEY_HOST = "daemon_host"
        const val KEY_PORT = "daemon_port"
        const val KEY_TOKEN = "device_token"
    }
}
