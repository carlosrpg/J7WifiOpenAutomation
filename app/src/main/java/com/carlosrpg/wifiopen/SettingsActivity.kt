package com.carlosrpg.wifiopen

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Service status / start, EngMode package picker, and a manual test run. */
class SettingsActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    private class App(val pkg: String?, val label: String) {
        override fun toString() = label
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val apps = listOf(App(null, getString(R.string.not_set))) +
            packageManager.getInstalledApplications(0)
                .map { App(it.packageName, "${packageManager.getApplicationLabel(it)} (${it.packageName})") }
                .sortedBy { it.label.lowercase() }

        bind(findViewById(R.id.engmode), WifiOpenService.KEY_ENGMODE, apps, "engmode")
        findViewById<View>(R.id.close).setOnClickListener { finish() }
        findViewById<View>(R.id.open_wifi).setOnClickListener {
            WifiOpenService.instance?.runNow() ?: openEngModeOnly()
        }
        findViewById<View>(R.id.start_service).setOnClickListener { startService() }

        bindPassword(findViewById(R.id.adb_password))
        bindSwitch(findViewById(R.id.adb_on_boot), WifiOpenService.KEY_ADB_ON_BOOT)
        findViewById<View>(R.id.enable_adb).setOnClickListener {
            WifiOpenService.instance?.runAdbNow() ?: openEngModeOnly()
        }
    }

    /** Encryption password: stored encrypted (Keystore AES-GCM). Shown only in this masked field, saved on edit. */
    private fun bindPassword(field: EditText) {
        val prefs = getSharedPreferences(WifiOpenService.PREFS, MODE_PRIVATE)
        SecurePassword.load(prefs)?.let { field.setText(it) }
        field.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) = SecurePassword.save(prefs, s.toString())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun bindSwitch(toggle: Switch, key: String) {
        val prefs = getSharedPreferences(WifiOpenService.PREFS, MODE_PRIVATE)
        toggle.isChecked = prefs.getBoolean(key, false)
        toggle.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            prefs.edit().putBoolean(key, checked).apply()
        }
    }

    // Poll the status while visible: the service can be (un)bound by the system at any time.
    private val statusLoop = object : Runnable {
        override fun run() {
            val running = WifiOpenService.instance != null
            findViewById<TextView>(R.id.service_status).apply {
                setText(if (running) R.string.service_running else R.string.service_stopped)
                setTextColor(if (running) Color.rgb(0x66, 0xBB, 0x6A) else Color.rgb(0xEF, 0x53, 0x50))
            }
            findViewById<View>(R.id.start_service).visibility = if (running) View.GONE else View.VISIBLE
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onResume() {
        super.onResume()
        statusLoop.run()
    }

    override fun onPause() {
        handler.removeCallbacks(statusLoop)
        super.onPause()
    }

    /** Service off: at least prove EngMode can be opened. The clicks need the service. */
    private fun openEngModeOnly() {
        val pkg = getSharedPreferences(WifiOpenService.PREFS, MODE_PRIVATE).getString(WifiOpenService.KEY_ENGMODE, null)
            ?: return toast(getString(R.string.err_engmode_not_set))
        val launch = WifiOpenService.launchIntentFor(packageManager, pkg)
            ?: return toast(getString(R.string.err_no_activity, pkg))
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            toast(getString(R.string.service_not_running))
        } catch (e: Exception) {
            toast(getString(R.string.err_launch_refused, launch.component?.className, e.message))
        }
    }

    private fun startService() {
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            // No adb grant: send the user to the system Accessibility list, if this head unit has one.
            val screens = listOf(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                Intent().setClassName("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity"),
            )
            for (screen in screens) {
                try {
                    startActivity(screen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return toast(getString(R.string.enable_in_list, getString(R.string.app_name)))
                } catch (e: Exception) { // not present, or not exported on this head unit
                }
            }
            return toast(getString(R.string.grant_needed))
        }
        val me = ComponentName(this, WifiOpenService::class.java)
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val others = Settings.Secure.getString(contentResolver, key).orEmpty().split(':')
            .filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
        val without = others.joinToString(":")
        val with = (others + me.flattenToShortString()).joinToString(":")
        // Android 11 won't rebind a service it marked as crashed (e.g. after the app was force-stopped) until the
        // service goes from listed to unlisted. A force-stop already unlists it, so: list, unlist, list again.
        Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Settings.Secure.putString(contentResolver, key, with)
        handler.postDelayed({ Settings.Secure.putString(contentResolver, key, without) }, 1_000)
        handler.postDelayed({ Settings.Secure.putString(contentResolver, key, with) }, 2_000)
        handler.postDelayed({ if (WifiOpenService.instance == null) toast(R.string.service_start_failed) }, 5_000)
    }

    private fun toast(message: Int) = toast(getString(message))
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun bind(spinner: Spinner, key: String, apps: List<App>, hint: String) {
        val prefs = getSharedPreferences(WifiOpenService.PREFS, MODE_PRIVATE)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, apps)
        val saved = prefs.getString(key, null)
        // Nothing saved yet: preselect the first app whose label or package loosely matches the hint
        // (letters/digits only, so "Eng Mode", "eng_mode" and "com.x.EngMode" all match).
        val index = if (saved != null) apps.indexOfFirst { it.pkg == saved }
        else apps.indexOfFirst { it.pkg != null && it.label.filter(Char::isLetterOrDigit).contains(hint, ignoreCase = true) }
        spinner.setSelection(index.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                prefs.edit().putString(key, apps[position].pkg).apply() // null pkg removes the key
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }
}
