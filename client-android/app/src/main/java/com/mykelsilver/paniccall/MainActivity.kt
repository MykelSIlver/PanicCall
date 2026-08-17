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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
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
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        askPerms.launch(arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS))
        CallService.start(this)
        bindService(Intent(this, CallService::class.java), conn, Context.BIND_AUTO_CREATE)
        setContent { MaterialTheme { Screen() } }
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
                val (label, color, action) = when {
                    state == "in_call" -> Triple("HANG UP", Color(0xFF802020)) { svc.engine.hangup() }
                    state == "ringing" -> Triple("ANSWER", Color(0xFF208020)) { svc.engine.answer() }
                    state == "idle" && canCall -> Triple("CALL ${peer.ifBlank { "…" }}",
                        Color(0xFFC02020)) { svc.engine.startCall() }
                    state == "idle" -> Triple("${peer.ifBlank { "…" }} is offline",
                        Color(0xFF404040)) { }
                    else -> Triple("…", Color(0xFF404040)) { }
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
                    onClick = {
                        val id = svc.engine.sendText(quickMessage)
                        svc.history.addSent(id, peer, quickMessage)
                    },
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
                    Text(sendStatus, fontSize = 12.sp, color = Color.Gray)
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
                        .putString("quickMessage", quickMsg.trim().take(200)).apply()
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
                }
            })
    }

    @Composable
    private fun HistoryDialog(history: MessageHistory, onDone: () -> Unit) {
        val entries by history.history.collectAsStateWithLifecycle()
        var showConfirmClear by remember { mutableStateOf(false) }
        // English-only, like the rest of the Android app so far (see
        // docs/ANDROID.md) -- Locale.US pinned deliberately, not the
        // device locale, so month names don't vary unexpectedly.
        val fmt = remember { SimpleDateFormat("MMM d hh:mm a", Locale.US) }

        AlertDialog(
            onDismissRequest = onDone,
            confirmButton = { TextButton(onClick = onDone) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = { showConfirmClear = true },
                    enabled = entries.isNotEmpty()) { Text("Clear") }
            },
            title = { Text("Message history") },
            text = {
                if (entries.isEmpty()) {
                    Text("No messages yet.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
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
                                        fontSize = 11.sp, color = Color.Gray)
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
