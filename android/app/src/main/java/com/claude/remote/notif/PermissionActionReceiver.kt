package com.claude.remote.notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.claude.remote.service.SessionService

/**
 * Catches taps on the Allow / Always / Deny / Voice action buttons on
 * permission notifications. Forwards the decision to SessionService.
 *
 * Voice path: parses the RemoteInput transcript. Maps obvious words to
 * decisions; anything else is treated as freeform input to send to Claude
 * as the next prompt.
 */
class PermissionActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val reqId = intent.getStringExtra(SessionService.EXTRA_REQ_ID) ?: return
        val rawDecision = intent.getStringExtra(SessionService.EXTRA_DECISION) ?: return

        val finalDecision = if (rawDecision == "voice") {
            val transcript = RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(NotifBuilder.KEY_VOICE_REPLY)?.toString()?.trim()?.lowercase()
                ?: return
            mapVoice(transcript) ?: run {
                // TODO(claude-code): freeform — send transcript as input to the session
                // instead of a permission decision.
                return
            }
        } else rawDecision

        val svcIntent = Intent(ctx, SessionService::class.java).apply {
            action = SessionService.ACTION_PERMISSION_RESPONSE
            putExtra(SessionService.EXTRA_REQ_ID, reqId)
            putExtra(SessionService.EXTRA_DECISION, finalDecision)
        }
        ctx.startService(svcIntent)
    }

    private fun mapVoice(t: String): String? = when {
        t.startsWith("allow always") || t.startsWith("always") -> "allow_always"
        t.startsWith("deny always") || t.startsWith("never") -> "deny_always"
        t.startsWith("allow") || t.startsWith("yes") || t.startsWith("ok") || t.startsWith("sure") -> "allow"
        t.startsWith("deny") || t.startsWith("no") || t.startsWith("stop") || t.startsWith("cancel") -> "deny"
        else -> null
    }
}
