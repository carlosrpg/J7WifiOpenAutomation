package com.carlosrpg.wifiopen

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
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
            WifiOpenService.instance?.runNow() ?: toast(R.string.service_not_running)
        }
        findViewById<View>(R.id.start_service).setOnClickListener { startService() }
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

    private fun startService() {
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            return toast(R.string.grant_needed)
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

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

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
