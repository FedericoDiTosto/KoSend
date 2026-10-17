package dev.kosend

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

object ServerController : CalibreServer.Listener {
    var state: CalibreServer.State = CalibreServer.State.Stopped
        private set

    var pickerOpen = false

    private var server: CalibreServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(CalibreServer.State) -> Unit>()

    val isRunning: Boolean get() = server != null

    fun addListener(l: (CalibreServer.State) -> Unit) = listeners.add(l)
    fun removeListener(l: (CalibreServer.State) -> Unit) = listeners.remove(l)

    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("kosend").apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(mode, "kosend").apply { setReferenceCounted(false); acquire() }

        val prefs = app.getSharedPreferences("kosend", Context.MODE_PRIVATE)
        val libraryUuid = prefs.getString("library_uuid", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("library_uuid", it).apply() }
        server = CalibreServer(Build.MODEL ?: "Android", libraryUuid, this).also { it.start() }
    }

    fun stop() {
        val s = server ?: return
        server = null
        s.stop()
        runCatching { multicastLock?.release() }
        runCatching { wifiLock?.release() }
        multicastLock = null
        wifiLock = null
        BookStore.resetSending()
    }

    fun sendPending() { server?.sendPending() }

    fun eject() { server?.eject() }

    fun localIp(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    override fun onStateChanged(state: CalibreServer.State) {
        main.post {
            val effective = if (server == null) CalibreServer.State.Stopped else state
            this.state = effective
            listeners.forEach { it(effective) }
        }
    }
}
