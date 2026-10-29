package com.aura.music.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A computer running AURA on the local network, as announced over mDNS. */
data class DiscoveredComputer(
    val serviceName: String,
    val name: String,
    val host: String,
    val port: Int,
    /** Short id derived from the computer's device id, to recognise paired ones. */
    val shortId: String,
    /** True while the computer shows a pairing code. */
    val pairing: Boolean,
)

/**
 * Finds AURA computers on the same Wi-Fi/hotspot with Android's NSD (mDNS/DNS-SD). Only runs
 * while something needs it (the devices screen, or reconnecting to a paired computer).
 */
class LinkDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _found = MutableStateFlow<Map<String, DiscoveredComputer>>(emptyMap())
    val found: StateFlow<Map<String, DiscoveredComputer>> = _found.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null
    private val toResolve = ConcurrentLinkedQueue<NsdServiceInfo>()
    @Volatile private var resolving = false
    private val executor = Executors.newSingleThreadExecutor()

    @Synchronized
    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = stopQuietly()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                toResolve += info
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                _found.value = _found.value - info.serviceName
            }
        }
        listener = l
        runCatching { nsd.discoverServices(LinkProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { listener = null }
    }

    @Synchronized
    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        toResolve.clear()
    }

    private fun stopQuietly() {
        synchronized(this) { listener = null }
    }

    /** NSD resolves one service at a time on older Android versions. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val next = toResolve.poll() ?: return
        resolving = true
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull { it is java.net.Inet4Address }?.hostAddress
                else info.host?.hostAddress
                val attrs = info.attributes.mapValues { (_, v) -> v?.let { String(it, Charsets.UTF_8) }.orEmpty() }
                if (host != null && attrs["v"] == LinkProtocol.VERSION.toString()) {
                    val computer = DiscoveredComputer(
                        serviceName = info.serviceName,
                        name = attrs["n"].orEmpty().ifBlank { info.serviceName }.take(40),
                        host = host,
                        port = info.port,
                        shortId = attrs["id"].orEmpty(),
                        pairing = attrs["pair"] == "1",
                    )
                    _found.value = _found.value + (info.serviceName to computer)
                }
                done()
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) nsd.resolveService(next, executor, callback) else nsd.resolveService(next, callback)
        }.onFailure { done() }
    }

    private fun done() {
        resolving = false
        resolveNext()
    }
}
