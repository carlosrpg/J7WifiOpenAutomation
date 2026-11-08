package com.carlosrpg.wifiopen

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner

/** Lets the user pick the EngMode package from the installed apps. */
class SettingsActivity : Activity() {

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
    }

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
