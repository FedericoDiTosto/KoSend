package dev.kosend

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {
    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        findViewById<TextView>(R.id.language_value).setText(AppLanguage.current().labelRes)
        findViewById<TextView>(R.id.version_value).text = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<android.view.View>(R.id.language_row).setOnClickListener { chooseLanguage() }
        findViewById<View>(R.id.update_row).setOnClickListener { checkForUpdates() }

        val deleteSwitch = findViewById<MaterialSwitch>(R.id.delete_after_send_switch)
        deleteSwitch.isChecked = BookStore.deleteAfterSend
        findViewById<android.view.View>(R.id.delete_after_send_row).setOnClickListener {
            deleteSwitch.toggle()
            BookStore.deleteAfterSend = deleteSwitch.isChecked
        }
    }

   private val onBooksChanged: () -> Unit = { showStorage() }

    override fun onStart() {
        super.onStart()
        BookStore.addListener(onBooksChanged)
        showStorage()
    }

    override fun onStop() {
        BookStore.removeListener(onBooksChanged)
        super.onStop()
    }

    private fun showStorage() {
        val (bytes, count) = BookStore.storageUsed()
        findViewById<TextView>(R.id.storage_value).text =
            resources.getQuantityString(R.plurals.settings_storage_value, count, count, formatSize(this, bytes))
    }

    private fun checkForUpdates() {
        if (checking) return
        checking = true
        val value = findViewById<TextView>(R.id.update_value)
        val spinner = findViewById<View>(R.id.update_progress)
        value.setText(R.string.update_checking)
        spinner.visibility = View.VISIBLE
        val current = BuildConfig.VERSION_NAME
        Thread {
            val result = runCatching { UpdateChecker.latest(current) }
            runOnUiThread {
                checking = false
                if (isDestroyed) return@runOnUiThread
                spinner.visibility = View.GONE
                val release = result.getOrNull()
                when {
                    release == null -> value.setText(R.string.update_failed)
                    UpdateChecker.isNewer(release.version, current) -> {
                        value.text = getString(R.string.update_available, release.version)
                        openUrl(release.url)
                    }
                    else -> value.text = getString(R.string.update_latest, current)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            android.widget.Toast.makeText(this, url, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun chooseLanguage() {
        val options = AppLanguage.entries
        val current = options.indexOf(AppLanguage.current())
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(options.map { getString(it.labelRes) }.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                if (which != current) options[which].apply()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
