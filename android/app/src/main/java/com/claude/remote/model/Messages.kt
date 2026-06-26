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
@SerialName("input")
data class Input(val session: String, val data: String) : Message

@Serializable
@SerialName("permission_response")
data class PermissionResponse(
    val id: String,
    val decision: String, // allow | deny | allow_always | deny_always
) : Message

// --- daemon -> phone ---

@Serializable
data class SessionInfo(
    val id: String,
    val name: String,
    val cwd: String,
    val status: String,
)

@Serializable
@SerialName("welcome")
data class Welcome(
    @SerialName("daemon_version") val daemonVersion: String,
    val hostname: String,
    val sessions: List<SessionInfo>,
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
) : Message

@Serializable
@SerialName("notification")
data class Notification(
    val session: String,
    val kind: String, // task_complete | error | permission_timeout | info
    val message: String,
    val ts: Long,
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
