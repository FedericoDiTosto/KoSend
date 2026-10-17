package dev.kosend

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class KoSendApp : Application() {

    private val main = Handler(Looper.getMainLooper())
    private val stopServer = Runnable { ServerController.stop() }

    override fun onCreate() {
        super.onCreate()
        BookStore.init(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                main.removeCallbacks(stopServer)
            }

            override fun onStop(owner: LifecycleOwner) {
                if (ServerController.pickerOpen) main.postDelayed(stopServer, PICKER_GRACE_MS)
                else ServerController.stop()
            }
        })
    }

    companion object {
        private const val PICKER_GRACE_MS = 2 * 60 * 1000L
    }
}
