package com.emon.wakemeup

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private var showTests = false
    /** A "please wait" message shown while searching or finding your position. */
    private var busy: String? = null

    private val green = Color.rgb(46, 125, 50)
    private val red = Color.rgb(211, 47, 47)
    private val amber = Color.rgb(239, 108, 0)
    private val blue = Color.rgb(25, 118, 210)
    private val grey = Color.rgb(97, 97, 97)

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
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Runs when you tap Share in Google Maps and choose Wake Me Up. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        intent.action = null // so turning the phone does not add the stop twice
        if (shared.isBlank()) return
        if (AppState.tripOn) {
            toast("End the trip first, then share the stop again.")
            return
        }
        busy = "Reading the place from Google Maps…"
        refreshPermissions(this)
        render()
        val near = lastKnownLocation()
        Thread {
            val place = SharedPlace.resolve(shared)
            // No exact position in the link? Look the place up by its name instead.
            val found = if (place.lat == null && place.searchText != null) geocode(place.searchText, near) else null
            runOnUiThread {
                busy = null
                render()
                when {
                    place.lat != null && place.lng != null ->
                        askName(place.name, place.lat, place.lng, "Exact position taken from Google Maps.")
                    !found.isNullOrEmpty() ->
                        askName(
                            place.name, found[0].latitude, found[0].longitude,
                            "Found by searching the name, so it may be slightly off. After saving, tap \"Check on map\".",
                        )
                    else -> showShareHelp("Could not find a position in what Google Maps sent.\n\n")
                }
            }
        }.start()
    }

    private fun showShareHelp(prefix: String = "") {
        AlertDialog.Builder(this)
            .setTitle("Pick your stop in Google Maps")
            .setMessage(
                prefix +
                    "1. In Google Maps, find your stop.\n" +
                    "2. Press and hold on the exact spot until a red pin appears. (A pin gives the most exact position.)\n" +
                    "3. Tap Share.\n" +
                    "4. Choose Wake Me Up from the list.\n" +
                    "5. Give the stop a name and tap OK."
            )
            .setPositiveButton("Open Google Maps") { _, _ ->
                val maps = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
                try { startActivity(maps) } catch (e: Exception) { toast("Google Maps was not found.") }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissions(this)
    }

    private fun ready() = AppState.locationAllowed && AppState.notificationsAllowed && AppState.locationSwitchedOn

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
        addText("Sleep on the bus. We'll wake you near your stop.", size = 15f).alpha = 0.7f
        space(12)

        // ---------- Setup (only shown when something is missing) ----------
        if (!ready()) {
            val card = card(amber)
            cardText(card, "Setup needed", 22f, true)
            if (!AppState.locationAllowed) {
                cardText(
                    card,
                    if (AppState.approximateOnly) "• Location is set to Approximate. Please change it to Precise."
                    else "• Allow location (choose \"Precise\" and \"While using the app\").",
                    15f,
                )
            }
            if (!AppState.notificationsAllowed) cardText(card, "• Allow notifications, so the alarm can show.", 15f)
            if (!AppState.locationSwitchedOn) cardText(card, "• Switch on Location on the phone.", 15f)

            if (!AppState.locationAllowed) {
                addButton("Allow location", color = blue) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        2,
                    )
                }
                addButton("Location did not ask? Open app settings") { openAppInfo() }
            }
            if (Build.VERSION.SDK_INT >= 33 && !AppState.notificationsAllowed) {
                addButton("Allow notifications", color = blue) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
            }
            if (!AppState.locationSwitchedOn) {
                addButton("Open Location settings", color = blue) {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
            space(16)
        }

        busy?.let {
            val card = card(grey)
            cardText(card, it, 16f, true)
            space(8)
        }

        if (AppState.tripOn) renderTrip() else renderPlanner()
    }

    /** The screen while a trip is running. */
    private fun renderTrip() {
        val card = card(green)
        cardText(card, "● Trip on", 22f, true)
        cardText(card, "To: ${AppState.tripStopName}", 17f)
        val distance = AppState.distanceMeters
        if (distance != null) {
            cardText(card, "${AppState.pretty(distance)} to go", 34f, true)
            cardText(card, "Rings at ${AppState.pretty(AppState.radiusMeters.toFloat())}  ·  updated ${AppState.lastUpdate}", 14f)
        }
        if (AppState.tripNote.isNotBlank()) cardText(card, AppState.tripNote, 15f)
        if (!AppState.tripIsDeskTest) cardText(card, "You can lock your phone now.", 14f)
        addButton("End trip", color = red) { TripService.stop(this); AlarmPlayer.stop(this) }
    }

    /** The screen before a trip: pick a stop, pick a distance, start. */
    private fun renderPlanner() {
        // ---------- 1. Stop ----------
        addText("1. Your stop", size = 18f, bold = true)
        if (AppState.stops.isEmpty()) {
            addText("No stops saved yet. Add the stop where you get off.", size = 14f).alpha = 0.7f
        }
        AppState.stops.forEachIndexed { index, stop ->
            val selected = index == AppState.selectedIndex
            addButton(if (selected) "✓  ${stop.name}" else stop.name, color = if (selected) green else grey) {
                AppState.select(this, index)
            }
        }
        addButton("+ Add a stop", color = blue) { askHowToAdd() }
        AppState.selectedStop?.let { stop ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(smallButton("Check on map") { openInMaps(stop) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this), LinearLayout.LayoutParams(dp(8), 1))
            row.addView(smallButton("Delete this stop") { confirmDelete(stop) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(row, matchWrap())
        }
        space(16)

        // ---------- 2. Distance ----------
        addText("2. Wake me this far before the stop", size = 18f, bold = true)
        val picker = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val minus = roundButton("−") { AppState.stepRadius(this, -1) }
        val number = TextView(this).apply {
            text = AppState.pretty(AppState.radiusMeters.toFloat())
            textSize = 44f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val plus = roundButton("+") { AppState.stepRadius(this, +1) }
        picker.addView(minus, LinearLayout.LayoutParams(dp(72), dp(72)))
        picker.addView(number, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        picker.addView(plus, LinearLayout.LayoutParams(dp(72), dp(72)))
        content.addView(picker, matchWrap())
        addText("A city bus covers 1 km in about 1–2 minutes. Pick more for trains or fast roads.", size = 13f, center = true).alpha = 0.7f
        space(16)

        // ---------- 3. Start ----------
        addButton("Start trip", color = green, big = true) { startTrip(simulate = false) }
        space(16)

        // ---------- Tests ----------
        addButton(if (showTests) "Hide tests & settings" else "Show tests & settings") {
            showTests = !showTests
            render()
        }
        if (showTests) {
            addButton("Test alarm sound") { AlarmPlayer.start(this, "Test alarm") }
            addButton("Desk test: pretend trip (rings in about 20 seconds)") { startTrip(simulate = true) }
            addButton("App info (battery: allow background)") { openAppInfo() }
        }
    }

    private fun startTrip(simulate: Boolean) {
        if (!ready()) {
            toast("Finish the setup at the top first.")
            return
        }
        val stop = AppState.selectedStop ?: if (simulate) SavedStop("Desk test stop", 0.0, 0.0) else null
        if (stop == null) {
            toast("Add a stop first.")
            return
        }
        TripService.start(this, stop, simulate)
    }

    // ---------- adding a stop ----------

    private fun askHowToAdd() {
        AlertDialog.Builder(this)
            .setTitle("Add a stop")
            .setItems(
                arrayOf("Pick it in Google Maps (easiest)", "Type the stop name or address", "Save where I am right now")
            ) { _, which ->
                when (which) {
                    0 -> showShareHelp()
                    1 -> askSearchText()
                    else -> saveWhereIAm()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askSearchText() {
        askText("Stop name or address", "Example: Heuston Station, Dublin", "") { query -> search(query) }
    }

    /** Looks the text up with Android's built-in address search (needs internet). */
    private fun search(query: String) {
        busy = "Searching for \"$query\"…"
        render()
        val near = lastKnownLocation()
        Thread {
            val results = geocode(query, near)
            runOnUiThread {
                busy = null
                render()
                when {
                    results == null -> toast("Search failed. Check your internet and try again.")
                    results.isEmpty() -> toast("Nothing found. Try adding the town, like \"Heuston Station, Dublin\".")
                    else -> pickResult(query, results)
                }
            }
        }.start()
    }

    /** Returns null if the search itself failed, or a (possibly empty) list of places. */
    @Suppress("DEPRECATION")
    private fun geocode(query: String, near: Location?): List<Address>? {
        if (!Geocoder.isPresent()) return null
        return try {
            val geocoder = Geocoder(this, Locale.getDefault())
            if (near != null) {
                // Look close to where you are first, so "Main Street" finds your Main Street.
                val local = geocoder.getFromLocationName(
                    query, 5,
                    near.latitude - 0.7, near.longitude - 1.0,
                    near.latitude + 0.7, near.longitude + 1.0,
                )
                if (!local.isNullOrEmpty()) return local
            }
            geocoder.getFromLocationName(query, 5) ?: emptyList()
        } catch (e: Exception) {
            null
        }
    }

    private fun pickResult(query: String, results: List<Address>) {
        val labels = results.map { it.getAddressLine(0) ?: "${it.latitude}, ${it.longitude}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Which one is your stop?")
            .setItems(labels) { _, which ->
                val a = results[which]
                askName(query, a.latitude, a.longitude, "Tip: after saving, tap \"Check on map\" to make sure it is the right place.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Listens to GPS for up to 25 seconds and keeps the most exact position. */
    @SuppressLint("MissingPermission")
    private fun saveWhereIAm() {
        if (!AppState.locationAllowed || !AppState.locationSwitchedOn) {
            toast("Finish the setup at the top first.")
            return
        }
        val lm = getSystemService(LocationManager::class.java)
        val handler = Handler(Looper.getMainLooper())
        var best: Location? = null
        var finished = false
        lateinit var listener: LocationListener

        fun finish() {
            if (finished) return
            finished = true
            try { lm.removeUpdates(listener) } catch (_: Exception) { }
            busy = null
            render()
            val found = best
            if (found == null) {
                toast("Could not find your position. Go near a window or outside and try again.")
            } else {
                askName("", found.latitude, found.longitude, "Position found, exact to about ${found.accuracy.toInt()} m.")
            }
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val current = best
                if (current == null || location.accuracy < current.accuracy) best = location
                if (location.hasAccuracy() && location.accuracy <= 30f) finish()
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        try {
            var any = false
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.allProviders.contains(provider) && lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                    any = true
                }
            }
            if (!any) {
                toast("Location is switched off on the phone.")
                return
            }
        } catch (e: SecurityException) {
            toast("Location permission is missing.")
            return
        }
        busy = "Finding your position… (up to 25 seconds)"
        render()
        handler.postDelayed({ finish() }, 25_000)
    }

    private fun askName(suggested: String, lat: Double, lng: Double, note: String) {
        askText("Name this stop", note, suggested) { name ->
            AppState.addStop(this, SavedStop(name, lat, lng))
        }
    }

    private fun confirmDelete(stop: SavedStop) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${stop.name}\"?")
            .setPositiveButton("Delete") { _, _ -> AppState.deleteSelected(this) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Opens the saved position in Google Maps (or any map app) so you can check it. */
    private fun openInMaps(stop: SavedStop) {
        val uri = Uri.parse("geo:${stop.lat},${stop.lng}?q=${stop.lat},${stop.lng}(${Uri.encode(stop.name)})")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            toast("No map app found.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        if (!AppState.locationAllowed) return null
        val lm = getSystemService(LocationManager::class.java)
        var newest: Location? = null
        try {
            for (provider in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(provider) ?: continue
                if (newest == null || l.time > newest.time) newest = l
            }
        } catch (_: Exception) { }
        return newest
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    // ---------- small helpers that build the screen ----------

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    /** A small pop-up with one text box. Calls [onOk] with the text if it is not empty. */
    private fun askText(title: String, message: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(initial)
            setSelection(text.length)
        }
        val holder = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(holder)
            .setPositiveButton("OK") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) toast("Nothing typed.") else onOk(text)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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

    private fun smallButton(label: String, onClick: () -> Unit): MaterialButton {
        val b = MaterialButton(this)
        b.text = label
        b.setAllCaps(false)
        b.textSize = 14f
        b.cornerRadius = dp(14)
        b.backgroundTintList = ColorStateList.valueOf(grey)
        b.setTextColor(Color.WHITE)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun addButton(label: String, color: Int? = null, big: Boolean = false, onClick: () -> Unit) {
        val b = MaterialButton(this)
        b.text = label
        b.setAllCaps(false)
        b.textSize = if (big) 30f else 16f
        b.cornerRadius = dp(14)
        if (color != null) {
            b.backgroundTintList = ColorStateList.valueOf(color)
            b.setTextColor(Color.WHITE)
        }
        if (big) b.minHeight = dp(150) else b.minHeight = dp(56)
        b.setOnClickListener { onClick() }
        content.addView(b, matchWrap())
    }
}

/** Re-checks what the phone currently allows, and stores it in AppState. */
fun refreshPermissions(context: Context) {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    AppState.locationAllowed = fine
    AppState.approximateOnly = !fine && granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    AppState.notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
    AppState.locationSwitchedOn =
        LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))
}
