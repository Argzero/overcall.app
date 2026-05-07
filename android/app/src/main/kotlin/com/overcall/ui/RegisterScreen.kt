package com.overcall.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.overcall.call.PhoneFormatter
import com.overcall.call.SimReader
import com.overcall.pay.RegisterFlow
import com.overcall.util.Base58
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.launch

@Composable
fun RegisterScreen(
    activity: ComponentActivity,
    resultSender: ActivityResultSender,
    owner: SolanaPublicKey,
    registerFlow: RegisterFlow,
    onDone: (sigBase58: String) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current.applicationContext
    val sim = remember { SimReader(ctx) }
    val formatter = remember { PhoneFormatter(ctx) }
    val scope = rememberCoroutineScope()

    var phoneInput by remember { mutableStateOf(prefillFromSim(sim, formatter) ?: "") }
    var status by remember { mutableStateOf<String?>(null) }
    var inFlight by remember { mutableStateOf(false) }

    val phonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            prefillFromSim(sim, formatter)?.let { phoneInput = it }
        }
    }

    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Register Phone", style = MaterialTheme.typography.titleLarge)
        Text("Owner: ${owner.base58().take(8)}…", style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = phoneInput,
            onValueChange = { phoneInput = it },
            label = { Text("Phone (E.164, e.g. +15551234567)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (!sim.hasPermission()) {
            TextButton(onClick = {
                phonePermissionLauncher.launch(android.Manifest.permission.READ_PHONE_NUMBERS)
            }) { Text("Read SIM number") }
        }

        Button(
            enabled = !inFlight && phoneInput.isNotBlank(),
            onClick = {
                val normalized = formatter.normalize(phoneInput, sim.networkCountryIso())
                if (normalized == null) {
                    status = "Phone number isn't valid E.164 — check format"
                    return@Button
                }
                inFlight = true
                status = "Submitting registration on devnet…"
                scope.launch {
                    when (val result = registerFlow.registerWithSolFee(
                        sender = resultSender,
                        owner = owner,
                        phoneE164 = normalized,
                    )) {
                        is RegisterFlow.Result.Success -> {
                            val sigBase58 = bytesToBase58(result.signature)
                            status = "Registered ${result.phoneE164}"
                            onDone(sigBase58)
                        }
                        is RegisterFlow.Result.Failure -> status = "Failed: ${result.message}"
                    }
                    inFlight = false
                }
            },
        ) { Text(if (inFlight) "Submitting…" else "Register") }

        TextButton(onClick = onBack) { Text("Back") }

        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it)
        }
    }
}

private fun prefillFromSim(sim: SimReader, formatter: PhoneFormatter): String? {
    val raw = sim.read() ?: return null
    return formatter.normalize(raw, sim.networkCountryIso())
}

private fun bytesToBase58(bytes: ByteArray): String = Base58.encode(bytes)
