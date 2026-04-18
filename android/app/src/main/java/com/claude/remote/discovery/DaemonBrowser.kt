package com.claude.remote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Browses the local network for daemons advertising "_claudecode._tcp."
 * Emits a list of resolved [Daemon]s as it discovers them.
 *
 * TODO(claude-code):
 *   - Coalesce add/remove events into a stable list (currently emits per-event)
 *   - Handle resolve failures + retries
 *   - Cache last-seen daemons in DataStore for offline display
 */
class DaemonBrowser(private val ctx: Context) {

    private val tag = "DaemonBrowser"
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager

    data class Daemon(val name: String, val host: String, val port: Int)

    fun browse(): Flow<Daemon> = callbackFlow {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(tag, "discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) { Log.d(tag, "discovery started") }
            override fun onDiscoveryStopped(serviceType: String) { Log.d(tag, "discovery stopped") }
            override fun onServiceFound(svc: NsdServiceInfo) {
                Log.d(tag, "found ${svc.serviceName}")
                nsd.resolveService(svc, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) {
                        Log.w(tag, "resolve failed: $errorCode")
                    }
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        val host = s.host?.hostAddress ?: return
                        trySend(Daemon(s.serviceName, host, s.port))
                    }
                })
            }
            override fun onServiceLost(svc: NsdServiceInfo) {
                Log.d(tag, "lost ${svc.serviceName}")
                // TODO: emit removal event
            }
        }

        nsd.discoverServices("_claudecode._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { nsd.stopServiceDiscovery(listener) }
    }
}
