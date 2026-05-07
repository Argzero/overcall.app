package com.overcall.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.overcall.attestation.AttestationKind
import com.overcall.call.PhoneFormatter
import com.overcall.pay.RevokeFlow
import com.overcall.pay.WalletInfo
import com.overcall.registry.PhoneRecord
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(
    activity: ComponentActivity,
    resultSender: ActivityResultSender,
    wallet: WalletInfo?,
    registration: PhoneRecord?,
    revokeFlow: RevokeFlow,
    onRegisterClick: () -> Unit,
    onRevoked: () -> Unit,
) {
    val ctx = LocalContext.current.applicationContext
    val formatter = remember { PhoneFormatter(ctx) }
    val scope = rememberCoroutineScope()

    var revokeConfirm by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        // Wallet card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Wallet", style = MaterialTheme.typography.titleMedium)
                if (wallet == null) {
                    Text("Not connected. Open Home and tap Connect Wallet.")
                } else {
                    Text("Pubkey: ${wallet.pubkey.base58().take(8)}…")
                    Text(
                        if (wallet.hardwareBacked)
                            "🔒 Hardware-backed (${wallet.displayLabel})"
                        else
                            "Wallet: ${wallet.displayLabel}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Registration card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Phone registration", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                if (registration == null) {
                    Text("No phone registered for this wallet on devnet.")
                    Spacer(Modifier.height(8.dp))
                    if (wallet != null) {
                        Button(onClick = onRegisterClick, modifier = Modifier.fillMaxWidth()) {
                            Text("Register a phone")
                        }
                    }
                } else {
                    Text("Phone: ${formatter.formatForDisplay(registration.phoneE164)}",
                        style = MaterialTheme.typography.bodyLarge)
                    Text("Owner: ${registration.owner.base58().take(8)}…",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Registered: ${formatTs(registration.registeredAt)}",
                        style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    AttestationLine(registration)

                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = onRegisterClick) { Text("Re-register") }
                        OutlinedButton(
                            onClick = { revokeConfirm = true },
                            enabled = wallet != null,
                        ) { Text("Revoke") }
                    }
                }
            }
        }

        // Web register handoff card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Web register handoff", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Want to use a web wallet (Phantom / Backpack / Solflare) on " +
                        "your laptop instead of MWA on this phone? Open the OverCall " +
                        "register page on your laptop, scan its QR with this phone's " +
                        "camera, and this app will pop up to do the hardware " +
                        "attestation.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (revokeConfirm && registration != null && wallet != null) {
        AlertDialog(
            onDismissRequest = { revokeConfirm = false },
            title = { Text("Revoke registration?") },
            text = {
                Text(
                    "This closes the on-chain PhoneRecord for " +
                        formatter.formatForDisplay(registration.phoneE164) +
                        ". Your registration fee is NOT refunded. " +
                        "Anyone could re-register this number afterward.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    revokeConfirm = false
                    status = "Revoking…"
                    scope.launch {
                        when (
                            val r = revokeFlow.revoke(
                                sender = resultSender,
                                owner = wallet.pubkey,
                                phoneE164 = registration.phoneE164,
                            )
                        ) {
                            is RevokeFlow.Result.Success -> {
                                status = "Revoked."
                                onRevoked()
                            }
                            is RevokeFlow.Result.Failure -> status = "Failed: ${r.message}"
                        }
                    }
                }) { Text("Revoke") }
            },
            dismissButton = {
                OutlinedButton(onClick = { revokeConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AttestationLine(record: PhoneRecord) {
    val label = when (record.attestationKind) {
        AttestationKind.NONE.toUByte() -> "Not attested (legacy)"
        AttestationKind.ANDROID_KEYSTORE.toUByte() -> "Android Keystore (hardware-backed)"
        AttestationKind.SEEKER_SEED_VAULT.toUByte() -> "Seeker Seed Vault (hardware-backed)"
        AttestationKind.WEB_DELEGATED.toUByte() -> "Web-delegated (phone-side handoff)"
        else -> "Unknown kind"
    }
    val attestedAt = if (record.attestedAt > 0) formatTs(record.attestedAt) else "—"
    Text("Attestation: $label", style = MaterialTheme.typography.bodySmall)
    Text("Attested: $attestedAt", style = MaterialTheme.typography.bodySmall)
}

private fun formatTs(unixSeconds: Long): String {
    if (unixSeconds <= 0) return "—"
    return Instant.ofEpochSecond(unixSeconds)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}
