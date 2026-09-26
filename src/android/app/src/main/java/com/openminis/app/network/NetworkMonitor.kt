package com.openminis.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.openminis.app.sandbox.RootfsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Monitors network connectivity changes using ConnectivityManager.NetworkCallback.
 * A flap merges connectivity status. It does not evict the connection pool.
 */
class NetworkMonitor {

    enum class NetworkStatus {
        CONNECTED,
        DISCONNECTED
    }

    companion object {
        private const val TAG = "NetworkMonitor"

        /**
         * [T-android-stale-conn-retry-hang] App-wide ConnectionPool shared by
         * every long-lived LLM provider OkHttpClient (OpenAI / Anthropic /
         * Gemini) — OkHttp explicitly supports sharing one pool across
         * clients. Routing them all through this instance is what lets
         * a flap must not clear it. A brief loss is merged back into the
         * current status instead of tearing idle sockets down.
         * Through a local VPN/proxy (e.g. clash at 127.0.0.1:7890) the TCP
         * socket to localhost survives network flaps, so the pool kept
         * handing the dead h2 tunnel to every retry — requests wrote into it
         * and hung forever waiting for response headers.
         */
        val sharedLLMConnectionPool = okhttp3.ConnectionPool(
            5, 5, java.util.concurrent.TimeUnit.MINUTES,
        )
    }

    private val _status = MutableStateFlow(NetworkStatus.DISCONNECTED)
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var okHttpClient: OkHttpClient? = null
    private var appContext: Context? = null

    /**
     * Background scope for DNS-refresh side effects. Kept off the callback
     * thread so ConnectivityManager isn't held up by file I/O.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flapLock = Any()
    private var pendingLoss: Runnable? = null
    private val flapHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Registers a network callback to observe connectivity changes.
     * Optionally accepts a shared OkHttpClient whose connection pool will be
     * evicted on network transitions.
     *
     * @param context Application or activity context.
     * @param client Optional shared OkHttpClient for connection pool eviction.
     */
    fun start(context: Context, client: OkHttpClient? = null) {
        okHttpClient = client
        appContext = context.applicationContext
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager

        val cm = connectivityManager ?: run {
            Log.e(TAG, "ConnectivityManager not available")
            return
        }

        // Set initial state
        val activeNetwork = cm.activeNetwork
        val capabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        _status.value = if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            NetworkStatus.CONNECTED
        } else {
            NetworkStatus.DISCONNECTED
        }
        Log.d(TAG, "Initial network status: ${_status.value}")

        // Mirror iOS NetworkMonitor.swift:23-26 — do an immediate DNS write so
        // resolv.conf is populated before the first NetworkCallback fires. The
        // callback is async and can lag by hundreds of ms on cold start.
        refreshSandboxDns("initial")

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                cancelPendingLoss()
                if (_status.value != NetworkStatus.CONNECTED) {
                    Log.d(TAG, "Network transition: ${_status.value} -> CONNECTED")
                    _status.value = NetworkStatus.CONNECTED
                    refreshSandboxDns("onAvailable")
                }
            }

            override fun onLost(network: Network) {
                scheduleLoss()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasInternet = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                if (hasInternet) {
                    cancelPendingLoss()
                    if (_status.value != NetworkStatus.CONNECTED) {
                        _status.value = NetworkStatus.CONNECTED
                        refreshSandboxDns("onCapabilitiesChanged")
                    }
                } else {
                    scheduleLoss()
                }
            }
        }

        networkCallback = callback
        cm.registerNetworkCallback(request, callback)
        Log.d(TAG, "Network monitoring started")
    }

    /**
     * Unregisters the network callback. Should be called during cleanup.
     */
    fun stop() {
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
                Log.d(TAG, "Network monitoring stopped")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Callback was not registered: ${e.message}")
            }
        }
        networkCallback = null
        connectivityManager = null
        okHttpClient = null
        appContext = null
        cancelPendingLoss()
    }

    private fun scheduleLoss() {
        synchronized(flapLock) {
            if (pendingLoss != null) return
            val loss = Runnable {
                synchronized(flapLock) { pendingLoss = null }
                if (_status.value != NetworkStatus.DISCONNECTED) {
                    Log.d(TAG, "Network transition: ${_status.value} -> DISCONNECTED")
                    _status.value = NetworkStatus.DISCONNECTED
                    refreshSandboxDns("onLost")
                }
            }
            pendingLoss = loss
            flapHandler.postDelayed(loss, NetworkFlapPolicy.DEBOUNCE_MS)
        }
    }

    private fun cancelPendingLoss() {
        synchronized(flapLock) {
            pendingLoss?.let { flapHandler.removeCallbacks(it) }
            pendingLoss = null
        }
    }

    /**
     * Refresh the sandbox rootfs' /etc/resolv.conf from the current system
     * DNS configuration. Runs on IO so we don't block the ConnectivityManager
     * callback thread with file I/O. Safe to call before the rootfs has been
     * extracted — [RootfsManager.refreshDns] no-ops when the rootfs is missing.
     *
     * Mirrors iOS NetworkMonitor.swift:26,60 which calls refreshDns() on every
     * NWPath update so already-running shells pick up the new nameservers the
     * next time they resolve a hostname (musl's getaddrinfo re-reads
     * resolv.conf on each lookup — no cache to invalidate).
     */
    private fun refreshSandboxDns(reason: String) {
        val ctx = appContext ?: return
        scope.launch {
            try {
                RootfsManager.getInstance(ctx).refreshDns()
                Log.d(TAG, "[DNS] sandbox resolv.conf refreshed ($reason)")
            } catch (t: Throwable) {
                Log.w(TAG, "[DNS] refresh failed ($reason): ${t.message}")
            }
        }
    }
}
