package com.mykelsilver.paniccall

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Same default as the Sailfish side, for consistency across platforms.
private const val DEFAULT_QUICK_MESSAGE = "Call me on MeshChat instead"

class MainActivity : ComponentActivity() {

    companion object {
        /** Set by the quick-message notification: open the history screen
         *  (where the message is, and where the reply field lives) rather
         *  than just bringing the app up on the main screen. */
        const val EXTRA_SHOW_HISTORY = "com.mykelsilver.paniccall.SHOW_HISTORY"

        /** Values for the "theme" preference. Stored as a string rather
         *  than an ordinal so a reordering can never silently change what
         *  an existing install means. */
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }

    /**
     * Current theme preference, hoisted onto the Activity so the settings
     * dialog can change it and have the whole UI recompose immediately
     * rather than only after a restart.
     */
    private var themeMode by mutableStateOf(THEME_SYSTEM)

    /**
     * Lives on the Activity rather than in a remember{} inside the
     * composable because two entry points outside composition need to set
     * it: onCreate (cold start from the notification) and onNewIntent
     * (the app was already running -- the common case, and the one that
     * needs android:launchMode="singleTop" to be delivered at all).
     */
    private var showHistory by mutableStateOf(false)

    private var service by mutableStateOf<CallService?>(null)
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            val s = (b as CallService.LocalBinder).service
            service = s
            // The app is visibly in the foreground right now, which is the
            // one moment the platform reliably allows claiming a
            // "while-in-use" permission. If the service came up from the
            // boot receiver it is still in specialUse-only standby mode,
            // so this is what makes calls possible again after a reboot.
            s.ensureCallCapable()
        }
        override fun onServiceDisconnected(n: ComponentName) { service = null }
    }

    private val askPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { }

    /**
     * Delivered when the notification is tapped while the app is already
     * running. Only reached because MainActivity is launchMode="singleTop"
     * and the PendingIntent carries FLAG_ACTIVITY_SINGLE_TOP: without
     * both, Android simply brings the existing task to the front and this
     * never fires, so the extra would work on a cold start and silently
     * do nothing the rest of the time.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)          // so a later getIntent() sees this one
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_HISTORY, false) == true)
            showHistory = true
    }

    /**
     * The resolved light/dark decision: the in-app preference, falling
     * back to the system setting. Single source of truth -- used both for
     * the colour scheme and for the few places that still need a
     * theme-aware literal colour, so those can never disagree.
     */
    @Composable
    private fun isDarkTheme(): Boolean = when (themeMode) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        askPerms.launch(arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS))
        CallService.start(this)
        bindService(Intent(this, CallService::class.java), conn, Context.BIND_AUTO_CREATE)
        themeMode = getSharedPreferences("paniccall", MODE_PRIVATE)
            .getString("theme", THEME_SYSTEM) ?: THEME_SYSTEM
        setContent {
            val dark = isDarkTheme()
            // Status- and navigation-bar icons have to be inverted to stay
            // legible against the bar background, and nothing else does
            // this for us: the app draws its own chrome via Compose.
            // SideEffect, not LaunchedEffect -- it must re-run on every
            // recomposition where `dark` changed, including the one caused
            // by saving the setting.
            SideEffect {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme() else lightColorScheme()
            ) {
                // Surface paints the background in the theme colour. Without
                // it the window background from themes.xml shows through,
                // which is only correct while the in-app setting agrees with
                // the system setting.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) { Screen() }
            }
        }
    }

    override fun onDestroy() {
        unbindService(conn)
        super.onDestroy()
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        val svc = service
        var showSettings by remember { mutableStateOf(false) }
        var quickMessage by remember {
            mutableStateOf(getSharedPreferences("paniccall", MODE_PRIVATE)
                .getString("quickMessage", DEFAULT_QUICK_MESSAGE) ?: DEFAULT_QUICK_MESSAGE)
        }

        Scaffold(topBar = {
            TopAppBar(title = { Text("PanicCall") }, actions = {
                TextButton(onClick = { showHistory = true }) { Text("History") }
                TextButton(onClick = { showSettings = true }) { Text("Settings") }
            })
        }) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (svc == null) { Text("Starting service…"); return@Column }

                val state by svc.engine.state.collectAsStateWithLifecycle()
                val peer by svc.engine.peerName.collectAsStateWithLifecycle()
                val online by svc.engine.peerOnline.collectAsStateWithLifecycle()
                val err by svc.engine.lastError.collectAsStateWithLifecycle()
                val speakerOn by svc.speakerOn.collectAsStateWithLifecycle()

                Text(when (state) {
                    "disconnected" -> "Not connected"
                    "connecting" -> "Connecting…"
                    "idle" -> if (online) "$peer is online" else "$peer is offline"
                    "ringing" -> "$peer is calling!"
                    "in_call" -> "In call with $peer"
                    else -> state
                }, style = MaterialTheme.typography.titleMedium)

                Spacer(Modifier.height(32.dp))

                // Greyed out and inert while idle-but-peer-offline: no point
                // letting the user tap into the "peer not online" error path
                // when the UI already knows the call would fail.
                val canCall = state == "idle" && online
                // The call/hangup/answer colours stay fixed across themes on
                // purpose: red means call, green means answer. They are
                // semantics, not decoration, and a panic button that changes
                // colour with the system theme is a worse panic button.
                // Only the DISABLED shade needs to follow the theme -- a dark
                // grey that reads as "off" on white disappears entirely into
                // a dark background.
                val disabledButton =
                    if (isDarkTheme()) Color(0xFF5A5A5A) else Color(0xFF404040)
                val (label, color, action) = when {
                    state == "in_call" -> Triple("HANG UP", Color(0xFF802020)) { svc.engine.hangup() }
                    state == "ringing" -> Triple("ANSWER", Color(0xFF208020)) { svc.engine.answer() }
                    state == "idle" && canCall -> Triple("CALL ${peer.ifBlank { "…" }}",
                        Color(0xFFC02020)) { svc.engine.startCall() }
                    state == "idle" -> Triple("${peer.ifBlank { "…" }} is offline",
                        disabledButton) { }
                    else -> Triple("…", disabledButton) { }
                }
                Box(
                    Modifier.size(240.dp).background(color, CircleShape)
                        .clickable(enabled = state != "idle" || online) { action() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = Color.White,
                        fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { svc.sendText(quickMessage) },
                    enabled = state == "idle"
                ) {
                    Text("Send: \"$quickMessage\"")
                }

                var sendStatus by remember { mutableStateOf("") }
                // Collected directly rather than via collectAsStateWithLifecycle:
                // textSent is a SharedFlow of one-shot events now, not state,
                // so there is no "current value" to snapshot. Collecting here
                // also means two identical sends in a row each show their own
                // confirmation, which the old nonce field existed to fake.
                LaunchedEffect(svc) {
                    svc.engine.textSent.collect { ev ->
                        sendStatus = if (ev.queued)
                            "Message queued — will arrive when ${peer.ifBlank { "…" }} comes online"
                        else "Message sent"
                        delay(4000)
                        sendStatus = ""
                    }
                }
                if (sendStatus.isNotEmpty()) {
                    Text(sendStatus, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (state == "in_call") {
                    Spacer(Modifier.height(24.dp))
                    FilterChip(
                        selected = speakerOn,
                        onClick = { svc.toggleSpeaker() },
                        label = { Text(if (speakerOn) "Speaker on" else "Earpiece") }
                    )
                }

                if (err.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text(err, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (showSettings) SettingsDialog(onDone = {
            showSettings = false
            service?.applySettings()
            quickMessage = getSharedPreferences("paniccall", MODE_PRIVATE)
                .getString("quickMessage", DEFAULT_QUICK_MESSAGE) ?: DEFAULT_QUICK_MESSAGE
            requestBatteryExemption()
        })
        if (showHistory && svc != null) HistoryDialog(svc.history) { showHistory = false }
    }

    @Composable
    private fun SettingsDialog(onDone: () -> Unit) {
        val p = getSharedPreferences("paniccall", MODE_PRIVATE)
        var url by remember { mutableStateOf(p.getString("url", "") ?: "") }
        var token by remember { mutableStateOf(p.getString("token", "") ?: "") }
        var name by remember { mutableStateOf(p.getString("name", "") ?: "") }
        var auto by remember { mutableStateOf(p.getBoolean("autoAnswer", true)) }
        var speaker by remember { mutableStateOf(p.getBoolean("defaultSpeaker", true)) }
        var presence by remember { mutableStateOf(p.getBoolean("notifyPresence", false)) }
        var textReceivedSound by remember {
            mutableStateOf(p.getBoolean("notifyTextReceived", true))
        }
        var quickMsg by remember {
            mutableStateOf(p.getString("quickMessage", DEFAULT_QUICK_MESSAGE) ?: DEFAULT_QUICK_MESSAGE)
        }
        var theme by remember {
            mutableStateOf(p.getString("theme", THEME_SYSTEM) ?: THEME_SYSTEM)
        }

        AlertDialog(
            onDismissRequest = onDone,
            confirmButton = {
                TextButton(onClick = {
                    p.edit().putString("url", url.trim())
                        .putString("token", token.trim())
                        .putString("name", name.trim())
                        .putBoolean("autoAnswer", auto)
                        .putBoolean("defaultSpeaker", speaker)
                        .putBoolean("notifyPresence", presence)
                        .putBoolean("notifyTextReceived", textReceivedSound)
                        .putString("quickMessage", quickMsg.trim().take(200))
                        .putString("theme", theme).apply()
                    // Applied straight away rather than on next launch:
                    // themeMode drives setContent's colour scheme.
                    themeMode = theme
                    onDone()
                }) { Text("Save") }
            },
            title = { Text("Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(url, { url = it },
                        label = { Text("Relay URL") }, singleLine = true)
                    OutlinedTextField(token, { token = it },
                        label = { Text("Token (64 hex characters)") }, singleLine = true)
                    OutlinedTextField(name, { name = it },
                        label = { Text("Your name") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(auto, { auto = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Auto-answer")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(speaker, { speaker = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Default to loudspeaker")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(presence, { presence = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Presence chirp (contact online/offline)")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(textReceivedSound, { textReceivedSound = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Message received sound")
                    }
                    OutlinedTextField(quickMsg, { quickMsg = it },
                        label = { Text("Quick message") }, singleLine = true)

                    Text("Appearance", fontWeight = FontWeight.Bold,
                        fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf(
                            THEME_SYSTEM to "System",
                            THEME_LIGHT to "Light",
                            THEME_DARK to "Dark",
                        ).forEach { (value, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)) {
                                RadioButton(
                                    selected = theme == value,
                                    onClick = { theme = value })
                                Text(label, fontSize = 13.sp)
                            }
                        }
                    }
                }
            })
    }

    @Composable
    private fun HistoryDialog(history: MessageHistory, onDone: () -> Unit) {
        val entries by history.history.collectAsStateWithLifecycle()
        var showConfirmClear by remember { mutableStateOf(false) }
        var reply by remember { mutableStateOf("") }
        // English-only, like the rest of the Android app so far (see
        // docs/ANDROID.md) -- Locale.US pinned deliberately, not the
        // device locale, so month names don't vary unexpectedly.
        val fmt = remember { SimpleDateFormat("MMM d hh:mm a", Locale.US) }

        // Sends whatever is in the reply field and clears it. Goes through
        // CallService.sendText(), which both sends AND records the row --
        // engine.sendText() alone would put the message on the wire but
        // leave nothing in history, which is what broke in v0.2.10.
        fun sendReply() {
            val text = reply.trim()
            if (text.isEmpty()) return
            service?.sendText(text)
            reply = ""
        }

        AlertDialog(
            onDismissRequest = onDone,
            confirmButton = { TextButton(onClick = onDone) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = { showConfirmClear = true },
                    enabled = entries.isNotEmpty()) { Text("Clear") }
            },
            title = { Text("Message history") },
            text = {
                Column {
                    if (entries.isEmpty()) {
                        Text("No messages yet.")
                    } else {
                        LazyColumn(Modifier.heightIn(max = 340.dp)) {
                            items(entries, key = { it.direction.name + it.id }) { e ->
                                Column(Modifier.padding(vertical = 6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (e.direction == MessageHistory.Direction.SENT)
                                                "You" else e.peer,
                                            fontWeight = FontWeight.Bold, fontSize = 13.sp
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(fmt.format(Date(e.timestampMs)),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        // Single checkmark, sent side only: none
                                        // yet = not confirmed delivered; check =
                                        // the peer's client has processed it.
                                        // No read-receipt distinction (v1 scope).
                                        if (e.direction == MessageHistory.Direction.SENT
                                            && e.status == MessageHistory.Status.DELIVERED) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("✓", color = Color(0xFF4CAF50),
                                                fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Text(e.message, fontSize = 14.sp)
                                }
                            }
                        }
                    }

                    // Ad-hoc reply. Deliberately here and not on the main
                    // screen: that stays a panic button plus one canned
                    // message, and this is where you already are when you
                    // have just read something and want to answer it --
                    // which is also where the message notification now
                    // lands you. See "Where the reins were loosened" in
                    // the README.
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = reply,
                            // The engine trims and caps at 200 characters;
                            // stop typing there rather than silently
                            // dropping the tail.
                            onValueChange = { if (it.length <= 200) reply = it },
                            placeholder = { Text("Write a reply…") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = { sendReply() },
                            enabled = reply.isNotBlank() && service != null
                        ) { Text("Send") }
                    }
                }
            })

        if (showConfirmClear) {
            AlertDialog(
                onDismissRequest = { showConfirmClear = false },
                title = { Text("Clear message history?") },
                text = { Text("This only clears your own copy on this phone. "
                        + "It cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        history.clear()
                        showConfirmClear = false
                    }) { Text("Clear") }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmClear = false }) { Text("Cancel") }
                })
        }
    }
}
