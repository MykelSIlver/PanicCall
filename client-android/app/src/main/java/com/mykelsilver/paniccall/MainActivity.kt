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

class MainActivity : ComponentActivity() {

    private var service by mutableStateOf<CallService?>(null)
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            service = (b as CallService.LocalBinder).service
        }
        override fun onServiceDisconnected(n: ComponentName) { service = null }
    }

    private val askPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        Scaffold(topBar = {
            TopAppBar(title = { Text("PanicCall") }, actions = {
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

                val (label, color, action) = when (state) {
                    "in_call" -> Triple("HANG UP", Color(0xFF802020)) { svc.engine.hangup() }
                    "ringing" -> Triple("ANSWER", Color(0xFF208020)) { svc.engine.answer() }
                    "idle" -> Triple("CALL ${peer.ifBlank { "…" }}",
                        Color(0xFFC02020)) { svc.engine.startCall() }
                    else -> Triple("…", Color(0xFF404040)) { }
                }
                Box(
                    Modifier.size(240.dp).background(color, CircleShape)
                        .clickable { action() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = Color.White,
                        fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
            requestBatteryExemption()
        })
    }

    @Composable
    private fun SettingsDialog(onDone: () -> Unit) {
        val p = getSharedPreferences("paniccall", MODE_PRIVATE)
        var url by remember { mutableStateOf(p.getString("url", "") ?: "") }
        var token by remember { mutableStateOf(p.getString("token", "") ?: "") }
        var name by remember { mutableStateOf(p.getString("name", "") ?: "") }
        var auto by remember { mutableStateOf(p.getBoolean("autoAnswer", true)) }

        AlertDialog(
            onDismissRequest = onDone,
            confirmButton = {
                TextButton(onClick = {
                    p.edit().putString("url", url.trim())
                        .putString("token", token.trim())
                        .putString("name", name.trim())
                        .putBoolean("autoAnswer", auto).apply()
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
                }
            })
    }
}
