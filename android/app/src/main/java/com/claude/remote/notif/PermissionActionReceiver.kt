package com.claude.remote.notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.claude.remote.service.SessionService

/**
 * Catches taps on the Allow / Deny / Reply action buttons on permission
 * notifications. Forwards the decision to SessionService.
 *
 * Reply path: parses the RemoteInput transcript. A bare decision word maps to
 * that decision; an empty/missing reply is a plain deny (so the request is
 * always answered and the notification cleared); anything else is treated as
 * "No, and here's what to do instead" — deny + forward the text as a prompt.
 */
class PermissionActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val reqId = intent.getStringExtra(SessionService.EXTRA_REQ_ID) ?: return
        val rawDecision = intent.getStringExtra(SessionService.EXTRA_DECISION) ?: return
        val session = intent.getStringExtra(SessionService.EXTRA_SESSION)

        var guidance: String? = null
        val finalDecision = if (rawDecision == "voice") {
            // orEmpty() so a null/missing results bundle becomes "" (-> plain
            // deny) instead of returning early and leaving the request hung +
            // the reply chip stuck in its 'sending…' spinner.
            val transcript = RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(NotifBuilder.KEY_VOICE_REPLY)?.toString()?.trim().orEmpty()
            when {
                transcript.isBlank() -> "deny"  // tapped Reply, sent nothing
                else -> {
                    // Strip trailing punctuation ("yes." / "ok!") before matching
                    // so a decorated bare word isn't mistaken for guidance.
                    val word = transcript.lowercase().trimEnd('.', '!', '?', ' ')
                    mapVoice(word) ?: run {
                        guidance = transcript  // keep original case for the prompt
                        "deny"
                    }
                }
            }
        } else rawDecision

        val msg = guidance
        val svcIntent = Intent(ctx, SessionService::class.java).apply {
            action = SessionService.ACTION_PERMISSION_RESPONSE
            putExtra(SessionService.EXTRA_REQ_ID, reqId)
            putExtra(SessionService.EXTRA_DECISION, finalDecision)
            if (msg != null) {
                putExtra(SessionService.EXTRA_SESSION, session)
                putExtra(SessionService.EXTRA_MESSAGE, msg)
            }
        }
        ctx.startService(svcIntent)
    }

    // Map a reply to a decision ONLY when it's a bare decision word/phrase.
    // Anything longer (e.g. "no, use Read instead") is treated as freeform
    // guidance by the caller, so a guidance sentence starting with "no" isn't
    // swallowed as a plain deny. Match the whole (trimmed) string, not a prefix.
    private fun mapVoice(t: String): String? = when (t) {
        "allow always", "always" -> "allow_always"
        "deny always", "never" -> "deny_always"
        "allow", "yes", "ok", "sure" -> "allow"
        "deny", "no", "stop", "cancel" -> "deny"
        else -> null
    }
}
