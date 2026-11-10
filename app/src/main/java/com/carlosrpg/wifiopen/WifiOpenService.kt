package com.carlosrpg.wifiopen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.wifi.WifiManager
import android.os.Bundle
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

        /** EngMode has no launcher icon on the J7, so fall back to its first activity other apps may open. */
        fun launchIntentFor(pm: PackageManager, pkg: String): Intent? {
            pm.getLaunchIntentForPackage(pkg)?.let { return it }
            val activities = try {
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
            val activity = activities?.firstOrNull { it.exported && it.enabled && it.permission == null } ?: return null
            return Intent().setClassName(pkg, activity.name)
        }
        private const val TAG = "J7WifiOpen"

        // EngMode's package is picked by the user in SettingsActivity.
        const val PREFS = "config"
        const val KEY_ENGMODE = "engmode_pkg"

        // ADB automation settings (SettingsActivity).
        const val KEY_ADB_ON_BOOT = "adb_on_boot"
        /** Set by BootReceiver, consumed once the accessibility service connects after a boot. */
        const val KEY_BOOT_PENDING = "boot_pending"

        /** Android Auto receiver and CarPlay app on the J7 head unit. */
        val PROJECTION_PKGS = setOf("com.google.android.projection.sink", "com.desaysv.vehicle.carplayapp")

        /** Backstop against an EngMode loop when a run fails; WiFi already on is the real "don't run" signal. */
        private const val COOLDOWN_MS = 60_000L

        private const val POLL_MS = 300L
        /** Overall backstop: a run that cannot finish is abandoned so the service never wedges. */
        private const val TIMEOUT_MS = 45_000L
        /** Each UI step (a screen or a dialog appearing) gets its own window before the run is failed. */
        private const val STEP_TIMEOUT_MS = 12_000L // room to scroll a long EngMode list
        /** Let the system settle after boot before driving EngMode. */
        private const val BOOT_DELAY_MS = 8_000L

        /** Keep the overlay up while the projection app redraws, so the launcher never flashes in between. */
        private const val RETURN_OVERLAY_MS = 800L

        /** EngMode's ADB entry after unlocking Encryption: "ADB" or "ADB Switch". */
        private val ADB_ITEM = Regex("""ADB(\s*Switch)?""", RegexOption.IGNORE_CASE)

        /** Ticks between scroll attempts while a list item is not on screen yet. */
        private const val SCROLL_EVERY_TICKS = 3

        /** Cover the screen while EngMode is driven. ACTION_CLICK works on views under an overlay. */
        private const val HIDE_WITH_OVERLAY = true

        /** The running service, for SettingsActivity's test buttons. Null when the service is not enabled. */
        @Volatile
        var instance: WifiOpenService? = null
            private set
    }

    /** Which EngMode task to drive. Both end by clicking "Open" and backing out. */
    enum class Flow { WIFI, ADB }

    private enum class Step { IDLE, CLICK_WIFI, CLICK_ENCRYPT, FILL_PW, CONFIRM_PW, CLICK_ADB, CLICK_OPEN, CLOSE }

    private val handler = Handler(Looper.getMainLooper())
    private val trigger = ConnectionTrigger(COOLDOWN_MS)
    private var flow = Flow.WIFI
    private var step = Step.IDLE
    private var runDeadline = 0L   // whole-run backstop
    private var stepDeadline = 0L  // current step
    private var closeAttempts = 0
    private var overlay: View? = null
    private var engModePkg = "" // captured at start() so a mid-run settings change can't break the run
    private var adbPassword = "" // decrypted only for the duration of an ADB run
    private var returnPkg: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: ""
        if (pkg !in PROJECTION_PKGS) return
        // Log.i, not Log.d: the J7 ROM drops debug logs.
        Log.i(TAG, "projection window pkg=$pkg cls=$cls step=$step")
        if (step != Step.IDLE) return
        val engMode = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ENGMODE, null)
        if (engMode == null) {
            Log.w(TAG, "Projection app in front but EngMode is not set: open the app to pick it")
            return
        }
        val wifiOn = getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        val decision = trigger.onProjectionWindow(SystemClock.elapsedRealtime(), wifiOn)
        Log.i(TAG, "wifiOn=$wifiOn -> $decision")
        if (decision == ConnectionTrigger.Decision.RUN) {
            start(engMode, Flow.WIFI, hidden = HIDE_WITH_OVERLAY, returnTo = pkg)
        }
    }

    override fun onServiceConnected() {
        instance = this
        consumeBootPending()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        super.onDestroy()
    }

    /** Called on connect (post-boot) and by BootReceiver: run the ADB flow once if "enable ADB on boot" is set. */
    fun consumeBootPending() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_BOOT_PENDING, false)) return
        prefs.edit().putBoolean(KEY_BOOT_PENDING, false).apply()
        if (!prefs.getBoolean(KEY_ADB_ON_BOOT, false)) return
        val engMode = prefs.getString(KEY_ENGMODE, null) ?: return Unit.also {
            Log.w(TAG, "ADB on boot is on but EngMode is not set")
        }
        Log.i(TAG, "Boot: scheduling ADB enable in ${BOOT_DELAY_MS}ms")
        handler.postDelayed({ if (step == Step.IDLE) start(engMode, Flow.ADB, hidden = HIDE_WITH_OVERLAY) }, BOOT_DELAY_MS)
    }

    /** Test button: WiFi flow now, ignoring the cooldown, leaving EngMode visible. */
    fun runNow() = testRun(Flow.WIFI)

    /** Test button: ADB flow now, leaving EngMode visible. */
    fun runAdbNow() = testRun(Flow.ADB)

    private fun testRun(which: Flow) {
        val engMode = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ENGMODE, null)
        when {
            engMode == null -> fail(getString(R.string.err_engmode_not_set))
            step != Step.IDLE -> fail(getString(R.string.err_already_running))
            // WiFi test stays visible (debugging aid); ADB always runs under its cover.
            else -> start(engMode, which, hidden = which == Flow.ADB)
        }
    }

    /** @param returnTo projection app to bring back to the front when done (null: stay where BACK leaves us). */
    private fun start(engMode: String, which: Flow, hidden: Boolean, returnTo: String? = null) {
        val launch = launchIntentFor(packageManager, engMode) ?: return fail(getString(R.string.err_no_activity, engMode))
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (which == Flow.ADB) {
            adbPassword = SecurePassword.load(prefs)?.takeIf { it.isNotEmpty() }
                ?: return fail(getString(R.string.err_adb_password_not_set))
        }
        Log.i(TAG, "Running EngMode $which automation with ${launch.component}")
        flow = which // before showOverlay(): it picks the cover by flow
        engModePkg = engMode
        returnPkg = returnTo
        if (hidden) showOverlay()
        try {
            // CLEAR_TASK so EngMode always starts on its main screen.
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        } catch (e: Exception) { // SecurityException (protected activity) or ActivityNotFoundException
            hideOverlay()
            return fail(getString(R.string.err_launch_refused, launch.component?.className, e.message))
        }
        closeAttempts = 0
        runDeadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        advance(if (which == Flow.WIFI) Step.CLICK_WIFI else Step.CLICK_ENCRYPT)
        handler.postDelayed(::tick, POLL_MS)
    }

    /** Move to the next step and give it a fresh window. */
    private fun advance(next: Step) {
        step = next
        missTicks = 0
        scrollForward = true
        stepDeadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MS
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        if (step != Step.CLOSE && (now > runDeadline || now > stepDeadline)) {
            onStepTimeout()
        }
        val root = engModeRoot()
        when (step) {
            Step.IDLE -> return
            // WiFi flow.
            Step.CLICK_WIFI -> if (root != null && clickText(root, "WIFI", exact = false)) advance(Step.CLICK_OPEN)
            // ADB flow.
            Step.CLICK_ENCRYPT -> if (root != null && clickOrScroll(root, "Encryption") { it.contains("encryption", true) })
                advance(Step.FILL_PW)
            Step.FILL_PW -> if (root != null && fillPassword(root, adbPassword)) advance(Step.CONFIRM_PW)
            Step.CONFIRM_PW -> if (root != null && (clickText(root, "Confirm", exact = true) || clickText(root, "OK", exact = true)))
                advance(Step.CLICK_ADB)
            // "ADB" or "ADB Switch"; never "Close ADB", "Open ADB", "Current State ADB"...
            Step.CLICK_ADB -> if (root != null && clickOrScroll(root, "ADB") { ADB_ITEM.matches(it) }) advance(Step.CLICK_OPEN)
            // Shared tail.
            Step.CLICK_OPEN -> if (root != null && clickOrScroll(root, "Open") { it.equals("Open", true) }) advance(Step.CLOSE)
            Step.CLOSE -> {
                // BACK until EngMode is no longer on screen (handles screen 2 -> screen 1 -> exit, plus dialogs).
                if (root == null || closeAttempts >= 6) {
                    Log.i(TAG, "Done ($flow)")
                    step = Step.IDLE
                    // The projection window coming back is re-checked by the trigger: WiFi on (or the
                    // cooldown, if the run failed) makes it a skip, so this cannot loop.
                    if (returnToProjection()) handler.postDelayed(::hideOverlay, RETURN_OVERLAY_MS)
                    else hideOverlay()
                    return
                }
                closeAttempts++
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
        handler.postDelayed(::tick, POLL_MS)
    }

    /** A step's window ran out. The password dialog is skippable (already unlocked); other steps fail the run. */
    private fun onStepTimeout() {
        val shown = engModeRoot() != null
        when (step) {
            // If encryption is already unlocked, no dialog appears: just go on to the ADB item / Confirm.
            Step.FILL_PW -> { Log.i(TAG, "No password field (already unlocked?), skipping to ADB"); advance(Step.CLICK_ADB); return }
            Step.CONFIRM_PW -> { Log.i(TAG, "No Confirm button, skipping to ADB"); advance(Step.CLICK_ADB); return }
            else -> {}
        }
        Log.i(TAG, "Stalled at $step; on screen: ${logVisibleTexts(engModeRoot())}")
        val what = when (step) {
            Step.CLICK_WIFI -> "WIFI"
            Step.CLICK_ENCRYPT -> "Encryption"
            Step.CLICK_ADB -> "ADB"
            Step.CLICK_OPEN -> "Open"
            else -> "?"
        }
        fail(if (!shown) getString(R.string.err_engmode_not_shown) else getString(R.string.err_button_not_found, what))
        step = Step.CLOSE
        stepDeadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MS
    }

    /** Reopens the Android Auto / CarPlay screen the run interrupted. @return true when it was launched. */
    private fun returnToProjection(): Boolean {
        val pkg = returnPkg ?: return false
        returnPkg = null
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return false.also {
            Log.w(TAG, "No launch intent for $pkg, staying on the current screen")
        }
        return try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "Returned to ${launch.component}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not reopen $pkg: ${e.message}")
            false
        }
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

    private var missTicks = 0
    private var scrollForward = true

    /**
     * Clicks the first node whose trimmed text satisfies [match]. EngMode's list only renders visible rows, so an
     * item revealed by Encryption may be off-screen: while nothing matches, scroll the list every few ticks
     * (forward until the end, then back).
     */
    private fun clickOrScroll(root: AccessibilityNodeInfo, query: String, match: (String) -> Boolean): Boolean {
        val node = root.findAccessibilityNodeInfosByText(query).firstOrNull { n ->
            n.text?.toString()?.trim()?.let(match) == true
        }
        if (node != null) {
            missTicks = 0
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            val ok = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            Log.i(TAG, "click '${node.text}' -> $ok")
            return ok
        }
        if (++missTicks % SCROLL_EVERY_TICKS == 0) {
            val list = firstScrollable(root) ?: return false
            val action = if (scrollForward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (!list.performAction(action)) scrollForward = !scrollForward // hit the end: turn around
            Log.i(TAG, "'$query' not visible, scrolled ${if (scrollForward) "forward" else "back"}")
        }
        return false
    }

    private fun firstScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) firstScrollable(node.getChild(i))?.let { return it }
        return null
    }

    /** Logs the labels on screen when a step stalls. Editable and password fields are skipped, never logged. */
    private fun logVisibleTexts(node: AccessibilityNodeInfo?, out: MutableList<String> = mutableListOf()): List<String> {
        if (node == null) return out
        if (!node.isEditable && !node.isPassword) node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        for (i in 0 until node.childCount) logVisibleTexts(node.getChild(i), out)
        return out
    }

    /** Types the password into the first editable field in the encryption dialog. */
    private fun fillPassword(root: AccessibilityNodeInfo, password: String): Boolean {
        val field = firstEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password)
        }
        val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.i(TAG, "fill password -> $ok") // never log the value or its length
        return ok
    }

    private fun firstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            firstEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun showOverlay() {
        if (!HIDE_WITH_OVERLAY || overlay != null) return
        val dp = resources.displayMetrics.density
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            val icon = if (flow == Flow.ADB) AdbAnimationView(context) else WifiAnimationView(context)
            addView(icon, LinearLayout.LayoutParams((160 * dp).toInt(), (160 * dp).toInt()))
            addView(TextView(context).apply {
                setText(if (flow == Flow.ADB) R.string.enabling_adb else R.string.connecting_wifi)
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
