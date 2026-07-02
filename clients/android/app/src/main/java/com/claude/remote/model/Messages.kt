package com.claude.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

/**
 * Wire protocol messages. Mirrors protocol/messages.md.
 *
 * Uses kotlinx.serialization's polymorphic discriminator on "type".
 * Decode with: Json { classDiscriminator = "type" }.decodeFromString<Message>(...)
 */
@Serializable
@JsonClassDiscriminator("type")
sealed interface Message

// --- phone -> daemon ---

@Serializable
@SerialName("hello")
data class Hello(val token: String) : Message

@Serializable
@SerialName("session_create")
data class SessionCreate(
    val name: String,
    val cwd: String,
    @SerialName("resume_id") val resumeId: String = "",
) : Message

@Serializable
@SerialName("session_attach")
data class SessionAttach(
    val id: String,
    @SerialName("replay_bytes") val replayBytes: Int = 0,
) : Message

@Serializable
@SerialName("list_sessions")
data class ListSessions(val cwd: String) : Message

@Serializable
@SerialName("delete_session")
data class DeleteSession(val cwd: String, val id: String) : Message

@Serializable
@SerialName("get_history")
data class GetHistory(
    val session: String,
    val cwd: String,
    val limit: Int = 0,
) : Message

@Serializable
@SerialName("kill_session")
data class KillSession(val id: String) : Message

@Serializable
@SerialName("take_over")
data class TakeOver(val id: String) : Message

@Serializable
@SerialName("check_path")
data class CheckPath(val path: String) : Message

@Serializable
@SerialName("list_dir")
data class ListDir(val path: String) : Message

@Serializable
@SerialName("set_handoff")
data class SetHandoff(val enabled: Boolean) : Message

@Serializable
@SerialName("input")
data class Input(val session: String, val data: String) : Message

@Serializable
@SerialName("resize")
data class Resize(val session: String, val cols: Int, val rows: Int) : Message

@Serializable
@SerialName("permission_response")
data class PermissionResponse(
    val id: String,
    val decision: String, // allow | deny | allow_always | deny_always
) : Message

@Serializable
@SerialName("file_upload")
data class FileUpload(
    val session: String,
    val filename: String,
    @SerialName("upload_id") val uploadId: String,
    val seq: Int,
    val total: Int,
    @SerialName("data") val dataBase64: String,
) : Message

// --- daemon -> phone ---

@Serializable
data class SessionInfo(
    val id: String,
    val name: String,
    val cwd: String,
    val status: String,
    @SerialName("started_at") val startedAt: Long = 0,
    @SerialName("last_activity") val lastActivity: Long = 0,
    val origin: String = "spawned", // spawned | adopted (external, e.g. VSCode)
)

@Serializable
@SerialName("welcome")
data class Welcome(
    @SerialName("daemon_version") val daemonVersion: String,
    val hostname: String,
    val sessions: List<SessionInfo>,
    @SerialName("handoff_enabled") val handoffEnabled: Boolean = false,
) : Message

@Serializable
@SerialName("handoff_state")
data class HandoffState(val enabled: Boolean) : Message

@Serializable
@SerialName("sessions_update")
data class SessionsUpdate(
    val sessions: List<SessionInfo>,
) : Message

@Serializable
@SerialName("path_checked")
data class PathChecked(
    val path: String,
    @SerialName("is_dir") val isDir: Boolean,
) : Message

@Serializable
@SerialName("dir_listing")
data class DirListing(
    val path: String,
    val parent: String, // "" when `path` is a filesystem root (no "up")
    val entries: List<String> = emptyList(), // immediate sub-directory names, sorted
) : Message

@Serializable
@SerialName("session_created")
data class SessionCreated(
    val id: String,
    val name: String,
    val cwd: String,
) : Message

@Serializable
data class ProjectSessionInfo(
    val id: String,
    val title: String,
    val modified: Long,
    val messages: Int,
)

@Serializable
@SerialName("project_sessions")
data class ProjectSessions(
    val cwd: String,
    val sessions: List<ProjectSessionInfo>,
) : Message

@Serializable
data class HistoryMessage(
    val role: String, // user | assistant
    val text: String,
    val ts: Long = 0,
)

@Serializable
@SerialName("history")
data class History(
    val session: String,
    val messages: List<HistoryMessage> = emptyList(),
) : Message

@Serializable
@SerialName("output")
data class Output(
    val session: String,
    val data: String,
    val stream: String, // stdout | stderr
) : Message

@Serializable
@SerialName("permission_request")
data class PermissionRequest(
    val id: String,
    val session: String,
    val tool: String,
    val input: JsonElement,
    val summary: String,
    @SerialName("received_at") val receivedAt: Long,
    @SerialName("session_name") val sessionName: String = "",
) : Message

@Serializable
@SerialName("permission_resolved")
data class PermissionResolved(
    val id: String,
    val reason: String, // answered | expired
    val decision: String = "", // winning decision when reason == "answered"
) : Message

@Serializable
@SerialName("notification")
data class Notification(
    val session: String,
    val kind: String, // task_complete | error | permission_timeout | warning | info
    val message: String,
    val ts: Long,
    @SerialName("session_name") val sessionName: String = "",
) : Message

@Serializable
@SerialName("file_uploaded")
data class FileUploaded(
    val session: String,
    @SerialName("upload_id") val uploadId: String,
    val path: String,
) : Message

@Serializable
@SerialName("error")
data class Error(
    val code: String,
    val message: String,
) : Message

// --- both directions ---

@Serializable
@SerialName("ping")
data class Ping(val ts: Long) : Message

@Serializable
@SerialName("pong")
data class Pong(val ts: Long) : Message
