package com.claude.remote.data

import android.content.Context
import com.claude.remote.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A saved Claude Code project on a daemon host — a name plus the absolute
 * folder path. Selecting one drops into the resume-or-new session flow.
 */
@Serializable
data class ProjectConfig(
    val id: String,
    val name: String,
    val path: String,
    val pinned: Boolean = false,
)

/**
 * A saved daemon/connection: where to reach it and its saved projects. Only
 * one daemon is connected at a time (see [ConnectionsState.activeDaemonId]).
 */
@Serializable
data class DaemonConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val token: String = "dev_placeholder",
    val projects: List<ProjectConfig> = emptyList(),
)

/** The whole persisted connections graph. */
@Serializable
data class ConnectionsState(
    val daemons: List<DaemonConfig> = emptyList(),
    val activeDaemonId: String? = null,
    // App-side display-name overrides for live sessions, keyed by session id.
    val sessionNames: Map<String, String> = emptyMap(),
    val schemaVersion: Int = 1,
)

/** Generate a fresh id for a daemon or project. */
fun newId(): String = UUID.randomUUID().toString()

/**
 * Persists the list of daemons (each with its projects) as one JSON blob in
 * the existing SharedPreferences file — no DataStore/Room dependency. The
 * service owns the observable layer; this is just the disk layer. Every
 * mutation reads, edits, re-serializes, and returns the new state.
 */
class ConnectionsStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("claude_remote", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): ConnectionsState {
        val raw = prefs.getString(KEY_JSON, null)
        return if (raw != null) {
            runCatching { json.decodeFromString(ConnectionsState.serializer(), raw) }
                .getOrElse { migrateFromLegacy() }
        } else {
            migrateFromLegacy()
        }
    }

    private fun save(state: ConnectionsState): ConnectionsState {
        prefs.edit()
            .putString(KEY_JSON, json.encodeToString(ConnectionsState.serializer(), state))
            .apply()
        return state
    }

    // --- daemon CRUD ---

    fun upsertDaemon(d: DaemonConfig): ConnectionsState {
        val s = load()
        val daemons = s.daemons.filterNot { it.id == d.id } + d
        return save(s.copy(daemons = daemons))
    }

    fun deleteDaemon(id: String): ConnectionsState {
        val s = load()
        val active = if (s.activeDaemonId == id) null else s.activeDaemonId
        return save(s.copy(daemons = s.daemons.filterNot { it.id == id }, activeDaemonId = active))
    }

    fun setActive(id: String?): ConnectionsState = save(load().copy(activeDaemonId = id))

    /** Set or clear (blank) a session's display-name override. */
    fun setSessionName(sessionId: String, name: String): ConnectionsState {
        val s = load()
        val names = s.sessionNames.toMutableMap()
        if (name.isBlank()) names.remove(sessionId) else names[sessionId] = name.trim()
        return save(s.copy(sessionNames = names))
    }

    // --- project CRUD (nested under a daemon) ---

    fun upsertProject(daemonId: String, p: ProjectConfig): ConnectionsState {
        val s = load()
        val daemons = s.daemons.map { d ->
            if (d.id != daemonId) d
            else d.copy(projects = d.projects.filterNot { it.id == p.id } + p)
        }
        return save(s.copy(daemons = daemons))
    }

    fun deleteProject(daemonId: String, projectId: String): ConnectionsState {
        val s = load()
        val daemons = s.daemons.map { d ->
            if (d.id != daemonId) d
            else d.copy(projects = d.projects.filterNot { it.id == projectId })
        }
        return save(s.copy(daemons = daemons))
    }

    /**
     * Build the initial state from the old single-daemon [Settings] keys (or
     * BuildConfig defaults). Runs on first launch and on a corrupt blob, and is
     * idempotent — it writes the new blob so subsequent loads decode normally.
     * Legacy keys are left in place as a rollback path.
     */
    private fun migrateFromLegacy(): ConnectionsState {
        val host = prefs.getString(LEGACY_HOST, null) ?: BuildConfig.DEFAULT_DAEMON_HOST
        val port = prefs.getInt(LEGACY_PORT, BuildConfig.DEFAULT_DAEMON_PORT)
        val token = prefs.getString(LEGACY_TOKEN, null) ?: "dev_placeholder"
        val daemon = DaemonConfig(id = newId(), name = "Default", host = host, port = port, token = token)
        return save(ConnectionsState(daemons = listOf(daemon), activeDaemonId = daemon.id))
    }

    private companion object {
        const val KEY_JSON = "connections_json"
        const val LEGACY_HOST = "daemon_host"
        const val LEGACY_PORT = "daemon_port"
        const val LEGACY_TOKEN = "device_token"
    }
}
