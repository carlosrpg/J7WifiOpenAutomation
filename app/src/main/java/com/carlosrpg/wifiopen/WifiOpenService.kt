package com.carlosrpg.wifiopen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WifiOpenService : AccessibilityService() {

    companion object {
        private const val TAG = "J7WifiOpen"

        // EngMode's package is picked by the user in SettingsActivity.
        const val PREFS = "config"
        const val KEY_ENGMODE = "engmode_pkg"

        /** Android Auto receiver and CarPlay app on the J7 head unit. */
        val PROJECTION_PKGS = setOf("com.google.android.projection.sink", "com.desaysv.vehicle.carplayapp")

        private const val COOLDOWN_MS = 10 * 60_000L

        private const val POLL_MS = 300L
        private const val TIMEOUT_MS = 15_000L

        /** Cover the screen while EngMode is driven. ACTION_CLICK works on views under an overlay. */
        private const val HIDE_WITH_OVERLAY = true

        /** The running service, for SettingsActivity's test button. Null when the service is not enabled. */
        @Volatile
        var instance: WifiOpenService? = null
            private set
    }

    private enum class Step { IDLE, CLICK_WIFI, CLICK_OPEN, CLOSE }

    private val handler = Handler(Looper.getMainLooper())
    private val trigger = ConnectionTrigger(COOLDOWN_MS)
    private var step = Step.IDLE
    private var deadline = 0L
    private var closeAttempts = 0
    private var overlay: View? = null
    private var engModePkg = "" // captured at start() so a mid-run settings change can't break the run

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: ""
        Log.d(TAG, "window pkg=$pkg cls=$cls")

        if (pkg !in PROJECTION_PKGS || step != Step.IDLE) return
        val engMode = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ENGMODE, null)
        if (engMode == null) {
            Log.w(TAG, "Projection app in front but EngMode is not set: open the app to pick it")
            return
        }
        if (trigger.onProjectionWindow(SystemClock.elapsedRealtime())) start(engMode, hidden = HIDE_WITH_OVERLAY)
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        super.onDestroy()
    }

    /** Test button in SettingsActivity: runs immediately, ignores the cooldown, and leaves EngMode visible. */
    fun runNow() {
        val engMode = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ENGMODE, null)
        when {
            engMode == null -> fail("Pick the EngMode app first")
            step != Step.IDLE -> fail("Automation is already running")
            else -> start(engMode, hidden = false)
        }
    }

    private fun start(engMode: String, hidden: Boolean) {
        val launch = launchIntentFor(engMode) ?: return fail("No activity of $engMode can be opened by other apps")
        Log.i(TAG, "Running EngMode WiFi automation with ${launch.component}")
        engModePkg = engMode
        if (hidden) showOverlay()
        try {
            // CLEAR_TASK so EngMode always starts on its main screen.
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        } catch (e: Exception) { // SecurityException (protected activity) or ActivityNotFoundException
            hideOverlay()
            return fail("Android refused to open ${launch.component?.className}: ${e.message}")
        }
        step = Step.CLICK_WIFI
        deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        closeAttempts = 0
        handler.postDelayed(::tick, POLL_MS)
    }

    private fun tick() {
        if (step != Step.CLOSE && SystemClock.uptimeMillis() > deadline) {
            fail(if (engModeRoot() == null) "EngMode never appeared on screen" else "Timed out at $step: button not found")
            step = Step.CLOSE
        }
        val root = engModeRoot()
        when (step) {
            Step.IDLE -> return
            Step.CLICK_WIFI -> if (root != null && clickText(root, "WIFI", exact = false)) step = Step.CLICK_OPEN
            Step.CLICK_OPEN -> if (root != null && clickText(root, "Open", exact = true)) step = Step.CLOSE
            Step.CLOSE -> {
                // BACK until EngMode is no longer on screen (handles screen 2 -> screen 1 -> exit, plus dialogs).
                if (root == null || closeAttempts >= 5) {
                    Log.i(TAG, "Done")
                    step = Step.IDLE
                    hideOverlay()
                    return
                }
                closeAttempts++
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
        handler.postDelayed(::tick, POLL_MS)
    }

    /** Launcher intent, or for hidden engineering apps (no launcher icon) their first openable activity. */
    private fun launchIntentFor(pkg: String): Intent? {
        packageManager.getLaunchIntentForPackage(pkg)?.let { return it }
        val activities = try {
            packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val activity = activities?.firstOrNull { it.exported && it.enabled && it.permission == null } ?: return null
        return Intent().setClassName(pkg, activity.name)
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun engModeRoot(): AccessibilityNodeInfo? =
        windows.firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName == engModePkg } }

    /** Clicks the first node (tree order) whose text matches, via its nearest clickable ancestor. */
    private fun clickText(root: AccessibilityNodeInfo, text: String, exact: Boolean): Boolean {
        val node = root.findAccessibilityNodeInfosByText(text) // case-insensitive "contains"
            .firstOrNull { !exact || it.text?.toString()?.trim().equals(text, ignoreCase = true) }
            ?: return false
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        val ok = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        Log.i(TAG, "click '${node.text}' -> $ok")
        return ok
    }

    private fun showOverlay() {
        if (!HIDE_WITH_OVERLAY || overlay != null) return
        val dp = resources.displayMetrics.density
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            addView(WifiAnimationView(context), LinearLayout.LayoutParams((160 * dp).toInt(), (160 * dp).toInt()))
            addView(TextView(context).apply {
                setText(R.string.connecting_wifi)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 28f
                setPadding(0, (24 * dp).toInt(), 0, 0)
            })
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not focusable: EngMode must stay the active window.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )
        getSystemService(WindowManager::class.java).addView(view, params)
        overlay = view
    }

    private fun hideOverlay() {
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        overlay = null
    }
}
