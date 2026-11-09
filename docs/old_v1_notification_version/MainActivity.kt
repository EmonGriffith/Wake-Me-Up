package com.emon.wakemeup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private val expanded = mutableSetOf<LogEntry>()
    private var showAdvanced = false

    private val green = Color.rgb(46, 125, 50)
    private val red = Color.rgb(211, 47, 47)
    private val amber = Color.rgb(239, 108, 0)
    private val blue = Color.rgb(25, 118, 210)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)
        // Let the alarm screen appear on top of the lock screen and switch the screen on.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        supportActionBar?.hide()
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        scroll.addView(content)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        AppState.onChange = { render() }
        refreshPermissions(this)
        render()
    }

    override fun onPause() {
        super.onPause()
        AppState.onChange = null
    }

    /** Draws the whole screen again from AppState. */
    private fun render() {
        content.removeAllViews()

        // ---------- Alarm ringing: nothing else matters ----------
        if (AppState.ringing) {
            addText("WAKE UP!", size = 40f, bold = true, center = true).setTextColor(red)
            addText("Your stop is coming.", size = 20f, center = true)
            addButton("STOP ALARM", color = red, big = true) { AlarmPlayer.stop(this) }
            space(24)
        }

        // ---------- Header ----------
        addText("Wake Me Up", size = 32f, bold = true)
        addText("Sleep on the bus. We'll wake you before your stop.", size = 15f).alpha = 0.7f
        space(12)

        // ---------- Status card ----------
        val ready = AppState.accessGranted && AppState.notificationsAllowed
        val card = card(if (ready) green else amber)
        if (ready) {
            cardText(card, "● Ready", 22f, true)
            cardText(card, "Start a transit journey in Google Maps and tap Start. You can lock your phone.", 15f)
        } else {
            cardText(card, "Setup needed", 22f, true)
            if (!AppState.accessGranted) {
                cardText(card, "1. Allow Wake Me Up to read notifications (only Google Maps is used).", 15f)
            }
            if (!AppState.notificationsAllowed) {
                cardText(card, "2. Allow notifications, so the alarm can show.", 15f)
            }
        }
        if (!AppState.accessGranted) {
            addButton("Allow notification access", color = blue) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && !AppState.notificationsAllowed) {
            addButton("Allow notifications", color = blue) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        space(16)

        // ---------- Stop picker ----------
        addText("Wake me when this many stops are left", size = 16f, bold = true)
        val picker = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val minus = roundButton("−") { AppState.setTrigger(this, AppState.triggerStops - 1) }
        val number = TextView(this).apply {
            text = AppState.triggerStops.toString()
            textSize = 56f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val plus = roundButton("+") { AppState.setTrigger(this, AppState.triggerStops + 1) }
        picker.addView(minus, LinearLayout.LayoutParams(dp(72), dp(72)))
        picker.addView(number, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        picker.addView(plus, LinearLayout.LayoutParams(dp(72), dp(72)))
        content.addView(picker, matchWrap())
        addText(
            if (AppState.triggerStops == 1) "Rings at 1 stop left." else "Rings at ${AppState.triggerStops} stops left (or fewer, if Maps skips a number).",
            size = 14f, center = true,
        ).alpha = 0.7f
        space(8)

        addText(
            if (AppState.armed) "Alarm: ARMED" else "Alarm: already rang this journey",
            size = 15f, bold = true, center = true,
        ).setTextColor(if (AppState.armed) green else amber)
        if (!AppState.armed) addButton("Re-arm") { AppState.armed = true }

        addButton("Test alarm sound") { AlarmPlayer.start(this, "Test alarm") }
        space(16)

        // ---------- Live log ----------
        addText("What Google Maps said", size = 18f, bold = true)
        addText("Latest: ${AppState.lastDecision}", size = 13f).alpha = 0.8f
        if (AppState.log.isEmpty()) {
            addText("Nothing yet. Start a Maps transit journey.", size = 14f).alpha = 0.6f
        }
        for (entry in AppState.log.take(15)) addLogCard(entry)
        space(16)

        // ---------- Advanced ----------
        addButton(if (showAdvanced) "Hide tests & settings" else "Show tests & settings") {
            showAdvanced = !showAdvanced
            render()
        }
        if (showAdvanced) {
            addButton("Fake Maps: 5 stops") { AlarmPlayer.postFakeMapsNotification(this, "Ride 5 stops") }
            addButton("Fake Maps: ${AppState.triggerStops} stops") {
                AlarmPlayer.postFakeMapsNotification(this, "Get off in ${AppState.triggerStops} stops")
            }
            addButton("Fake in 15 seconds (lock phone)") {
                Toast.makeText(this, "Lock your phone now. Fake notification in 15 seconds.", Toast.LENGTH_LONG).show()
                val app = applicationContext
                val stops = AppState.triggerStops
                Handler(Looper.getMainLooper()).postDelayed({
                    AlarmPlayer.postFakeMapsNotification(app, "Get off in $stops stops")
                }, 15_000)
            }
            addButton("Notification access settings") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            addButton("App info (battery: allow background)") {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }
            addButton("Clear log") {
                AppState.log.clear()
                expanded.clear()
                render()
            }
        }
    }

    // ---------- small helpers that build the screen ----------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun space(heightDp: Int) {
        content.addView(TextView(this), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun addText(s: String, size: Float = 16f, bold: Boolean = false, center: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = s
        tv.textSize = size
        if (bold) tv.setTypeface(tv.typeface, Typeface.BOLD)
        if (center) tv.gravity = Gravity.CENTER
        tv.setPadding(0, dp(4), 0, dp(4))
        content.addView(tv, matchWrap())
        return tv
    }

    private fun card(color: Int): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(14), dp(16), dp(14))
        box.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(color)
        }
        val params = matchWrap()
        params.bottomMargin = dp(8)
        content.addView(box, params)
        return box
    }

    private fun cardText(box: LinearLayout, s: String, size: Float, bold: Boolean = false) {
        val tv = TextView(this)
        tv.text = s
        tv.textSize = size
        tv.setTextColor(Color.WHITE)
        if (bold) tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.setPadding(0, dp(2), 0, dp(2))
        box.addView(tv)
    }

    private fun roundButton(label: String, onClick: () -> Unit): MaterialButton {
        val b = MaterialButton(this)
        b.text = label
        b.textSize = 30f
        b.cornerRadius = dp(36)
        b.setPadding(0, 0, 0, 0)
        b.insetTop = 0
        b.insetBottom = 0
        b.setOnClickListener { onClick() }
        return b
    }

    private fun addButton(label: String, color: Int? = null, big: Boolean = false, onClick: () -> Unit) {
        val b = MaterialButton(this)
        b.text = label
        b.setAllCaps(false)
        b.textSize = if (big) 30f else 16f
        b.cornerRadius = dp(14)
        if (color != null) b.backgroundTintList = ColorStateList.valueOf(color)
        if (big) b.minHeight = dp(150) else b.minHeight = dp(56)
        b.setOnClickListener { onClick() }
        content.addView(b, matchWrap())
    }

    private fun addLogCard(entry: LogEntry) {
        val isOpen = entry in expanded
        val body = buildString {
            append(entry.time).append("  ·  ").append(entry.source).append('\n')
            append(entry.text).append('\n')
            append("→ ").append(entry.decision)
            if (isOpen) append("\n\n").append(entry.details)
        }
        val tv = TextView(this)
        tv.text = body
        tv.textSize = if (isOpen) 12f else 14f
        if (isOpen) tv.typeface = Typeface.MONOSPACE
        tv.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.argb(30, 120, 120, 120))
        }
        tv.setPadding(dp(12), dp(10), dp(12), dp(10))
        tv.setOnClickListener {
            if (isOpen) expanded.remove(entry) else expanded.add(entry)
            render()
        }
        val params = matchWrap()
        params.topMargin = dp(8)
        content.addView(tv, params)
    }
}

fun refreshPermissions(context: Context) {
    AppState.accessGranted =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    AppState.notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}
