package dev.kosend

import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {

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
