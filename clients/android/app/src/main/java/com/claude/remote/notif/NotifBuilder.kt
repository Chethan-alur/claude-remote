package com.claude.remote.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.claude.remote.R
import com.claude.remote.model.PermissionRequest
import com.claude.remote.service.SessionService

/**
 * Builds the three notification kinds:
 *   1. Persistent status notification (for the foreground service)
 *   2. Permission request — high priority, three action buttons + voice reply
 *   3. Task complete — heads-up, no actions
 *
 * Actions launch [PermissionActionReceiver] with the chosen decision.
 *
 * TODO(claude-code):
 *   - Use full-screen intent (USE_FULL_SCREEN_INTENT) for permission requests
 *     so they wake the screen
 *   - Pretty-print the tool input (e.g. "Bash: rm -rf node_modules") in
 *     bigText style for richer detail when expanded
 */
class NotifBuilder(private val ctx: Context) {

    private val mgr = ctx.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        mgr.createNotificationChannel(
            NotificationChannel(
                CHAN_STATUS,
                ctx.getString(R.string.notif_channel_status),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = ctx.getString(R.string.notif_channel_status_desc) }
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHAN_PERMISSIONS,
                ctx.getString(R.string.notif_channel_permissions),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = ctx.getString(R.string.notif_channel_permissions_desc)
                enableVibration(true)
                setBypassDnd(false)
            }
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHAN_COMPLETE,
                ctx.getString(R.string.notif_channel_complete),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = ctx.getString(R.string.notif_channel_complete_desc) }
        )
    }

    private val accent = ctx.getColor(R.color.brand_accent)

    fun buildStatus(text: String) = NotificationCompat.Builder(ctx, CHAN_STATUS)
        .setSmallIcon(R.drawable.ic_stat_claude)
        .setColor(accent)
        .setContentTitle("Claude Remote")
        .setContentText(text)
        .setOngoing(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    /** Re-post the ongoing status notification with new text. */
    fun updateStatus(text: String) = mgr.notify(STATUS_NOTIF_ID, buildStatus(text))

    fun postPermissionRequest(req: PermissionRequest) {
        val notifId = req.id.hashCode()
        val n = NotificationCompat.Builder(ctx, CHAN_PERMISSIONS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setColor(accent)
            .setContentTitle("${req.tool} permission requested")
            .setContentText(req.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(req.summary))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL) // wakes screen, bypasses DnD-lite
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // show on lock screen
            .setAutoCancel(true)
            // Android renders at most 3 actions, so the 3rd is an inline reply
            // that denies and forwards your text as the next prompt. "Always"
            // (allow_always) is reachable by typing "always" into that reply.
            .addAction(actionFor(req.id, "allow", R.string.notif_perm_action_allow))
            .addAction(actionFor(req.id, "deny", R.string.notif_perm_action_deny))
            .addAction(voiceReplyAction(req.id, req.session))
            .build()
        mgr.notify(notifId, n)
    }

    fun cancelPermission(reqId: String) = mgr.cancel(reqId.hashCode())

    fun postTaskComplete(sessionId: String, message: String) {
        val n = NotificationCompat.Builder(ctx, CHAN_COMPLETE)
            .setSmallIcon(R.drawable.ic_stat_claude)
            .setColor(accent)
            .setContentTitle("Task complete")
            .setContentText(message)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        mgr.notify(sessionId.hashCode() xor TASK_COMPLETE_SALT, n)
    }

    private fun actionFor(reqId: String, decision: String, labelRes: Int): NotificationCompat.Action {
        val intent = Intent(ctx, PermissionActionReceiver::class.java).apply {
            action = SessionService.ACTION_PERMISSION_RESPONSE
            putExtra(SessionService.EXTRA_REQ_ID, reqId)
            putExtra(SessionService.EXTRA_DECISION, decision)
        }
        val pi = PendingIntent.getBroadcast(
            ctx,
            (reqId + decision).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, ctx.getString(labelRes), pi).build()
    }

    private fun voiceReplyAction(reqId: String, session: String): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_VOICE_REPLY)
            .setLabel(ctx.getString(R.string.notif_perm_reply_hint))
            .setAllowFreeFormInput(true)
            .build()
        val intent = Intent(ctx, PermissionActionReceiver::class.java).apply {
            action = SessionService.ACTION_PERMISSION_RESPONSE
            putExtra(SessionService.EXTRA_REQ_ID, reqId)
            putExtra(SessionService.EXTRA_DECISION, "voice") // receiver maps transcript -> decision
            // Carry the session so a freeform reply can be routed to it as a prompt.
            putExtra(SessionService.EXTRA_SESSION, session)
        }
        val pi = PendingIntent.getBroadcast(
            ctx, ("voice$reqId").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(0, ctx.getString(R.string.notif_perm_action_voice_label), pi)
            .addRemoteInput(remoteInput)
            .build()
    }

    companion object {
        const val STATUS_NOTIF_ID = 1001
        private const val TASK_COMPLETE_SALT = 0x7ACE0
        const val KEY_VOICE_REPLY = "voice_reply_text"
        private const val CHAN_STATUS = "status"
        private const val CHAN_PERMISSIONS = "permissions"
        private const val CHAN_COMPLETE = "complete"
    }
}
