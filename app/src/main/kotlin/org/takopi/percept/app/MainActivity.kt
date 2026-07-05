package org.takopi.percept.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionsGranted = hasRequiredPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionsGranted = hasRequiredPermissions()
        val controller = PerceptRuntime.controller(this)
        val settings = PerceptSettings(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (permissionsGranted) {
                        SessionScreen(
                            controller = controller,
                            settings = settings,
                            onStart = { PerceptRecordingService.start(this) },
                            onStop = { PerceptRecordingService.stop(this) },
                        )
                    } else {
                        PermissionScreen(
                            onRequestPermissions = {
                                permissionLauncher.launch(requiredPermissions())
                            },
                        )
                    }
                }
            }
        }
        if (!permissionsGranted) {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
}

@Composable
private fun SessionScreen(
    controller: SessionController,
    settings: PerceptSettings,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var endpoint by remember { mutableStateOf(settings.endpointUrl) }
    var token by remember { mutableStateOf(settings.bearerToken) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Percept", style = MaterialTheme.typography.headlineMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onStart, enabled = !state.running) { Text("Start") }
            Button(onClick = onStop, enabled = state.running) { Text("Stop") }
            Text(
                text = state.sessionId ?: state.lastSessionId ?: "no session",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text(
            text = "events ${state.eventsIngested} · dropped ${state.eventsDropped}",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                enabled = !state.running && (state.lastSessionId != null),
                onClick = { scope.launch { controller.exportLastSessionBundle() } },
            ) { Text("Export bundle") }
            OutlinedButton(
                enabled = !state.running && (state.lastSessionId != null),
                onClick = { scope.launch { controller.exportAndUpload() } },
            ) { Text("Upload") }
        }
        state.lastExportPath?.let { path ->
            Text(text = "exported: $path", style = MaterialTheme.typography.bodySmall)
        }
        state.lastUploadStatus?.let { status ->
            Text(text = "upload: $status", style = MaterialTheme.typography.bodySmall)
        }
        state.lastError?.let { error ->
            Text(
                text = "error: $error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = endpoint,
            onValueChange = { value ->
                endpoint = value
                settings.endpointUrl = value
            },
            label = { Text("Sync endpoint URL") },
            singleLine = true,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = token,
            onValueChange = { value ->
                token = value
                settings.bearerToken = value
            },
            label = { Text("Bearer token") },
            singleLine = true,
        )

        HorizontalDivider()
        Text(text = "Live events", style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(state.recentEvents) { entry ->
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = "${entry.observedAtIso}  ${entry.valueKind}  ${entry.eventIdPrefix}…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    entry.preview?.let { preview ->
                        Text(text = preview, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionScreen(
    onRequestPermissions: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Percept", style = MaterialTheme.typography.headlineMedium)
        Text(
            modifier = Modifier.padding(top = 12.dp),
            text = "Camera and microphone permissions required",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(
            modifier = Modifier.padding(top = 24.dp),
            onClick = onRequestPermissions,
        ) {
            Text(text = "Grant permissions")
        }
    }
}
