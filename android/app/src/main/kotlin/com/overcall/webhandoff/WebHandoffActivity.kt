package com.overcall.webhandoff

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.overcall.attestation.AttestationKind
import com.overcall.attestation.KeystoreAttestor
import com.overcall.call.PhoneFormatter
import com.overcall.call.SimReader
import com.overcall.invite.InviteQr
import com.overcall.util.Base58
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lands here when the laptop's web register portal QR is scanned. The
 * inbound URI carries the phone number the user entered on the laptop,
 * the laptop's wallet pubkey (the on-chain owner), and a per-session
 * nonce. We validate the phone matches this device's SIM (warn-but-
 * allow on mismatch, since prepaid/MVNO SIMs sometimes refuse to expose
 * line1Number), generate a hardware-attested Keystore key bound to the
 * (phone, wallet) pair, and render a return QR carrying the attestation
 * hash. The user shows the return QR to their laptop, which finishes
 * the on-chain register tx.
 *
 * The on-chain `register_with_sol_fee` accepts any non-zero hash + valid
 * kind, so the only thing we strictly *must* compute on this side is the
 * hash. The challenge embedded in the leaf cert is what binds the chain
 * to the (phone, wallet) pair — anyone can later verify off-chain that
 * the leaf cert's challenge equals
 * sha256("overcall.attest" || phone || wallet.bytes).
 */
class WebHandoffActivity : ComponentActivity() {

    private val attestor = KeystoreAttestor()

    private val phonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inbound = parseIntent(intent)
        if (!hasReadPhoneNumbersPermission()) {
            phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_NUMBERS)
        }
        setContent {
            MaterialTheme {
                Screen(
                    inbound = inbound,
                    onClose = { finish() },
                    runAttestation = ::runAttestation,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // If the user re-scans a different inbound QR while this activity
        // is foregrounded, restart cleanly so state doesn't leak across
        // sessions (different nonce, different wallet).
        setIntent(intent)
        recreate()
    }

    private fun parseIntent(intent: Intent?): WebHandoff.Inbound? {
        val uri = intent?.data?.toString() ?: return null
        return WebHandoff.parseInbound(uri)
    }

    private fun hasReadPhoneNumbersPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_PHONE_NUMBERS,
        ) == PackageManager.PERMISSION_GRANTED

    private suspend fun runAttestation(inbound: WebHandoff.Inbound): AttestResult {
        return withContext(Dispatchers.Default) {
            try {
                val raw = attestor.attest(inbound.phoneE164, inbound.wallet)
                // Re-tag as web-delegated since the Keystore generated this on
                // behalf of a *web* wallet, not the in-app MWA wallet.
                val tagged = raw.copy(kind = AttestationKind.WEB_DELEGATED)
                val returnUri = WebHandoff.buildOutbound(inbound.nonce, tagged.hash)
                // Log the return URI so the emulator-test helper script
                // (scripts/grab-attestation-hash.sh) can scrape it
                // without requiring the host to point a webcam at the
                // emulator window. Emits an unredacted hash, which is
                // fine — the hash binds nothing on its own; the leaf
                // cert's challenge is what actually authenticates.
                Log.i(LOG_TAG, returnUri)
                AttestResult.Ok(
                    hashBase58 = Base58.encode(tagged.hash),
                    returnUri = returnUri,
                )
            } catch (t: Throwable) {
                AttestResult.Error(t.message ?: t::class.simpleName ?: "attestation failed")
            }
        }
    }

    companion object {
        private const val LOG_TAG = "OverCall/WebHandoff"
    }
}

private sealed interface AttestResult {
    data class Ok(val hashBase58: String, val returnUri: String) : AttestResult
    data class Error(val message: String) : AttestResult
}

@Composable
private fun Screen(
    inbound: WebHandoff.Inbound?,
    onClose: () -> Unit,
    runAttestation: suspend (WebHandoff.Inbound) -> AttestResult,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Web register handoff", style = MaterialTheme.typography.titleLarge)

            if (inbound == null) {
                Text(
                    "This URL is malformed. Open the QR shown by overcall.app on " +
                        "your laptop, and scan it with this phone's camera.",
                )
                Button(onClick = onClose) { Text("Close") }
                return@Column
            }

            HandoffBody(inbound = inbound, runAttestation = runAttestation, onClose = onClose)
        }
    }
}

@Composable
private fun HandoffBody(
    inbound: WebHandoff.Inbound,
    runAttestation: suspend (WebHandoff.Inbound) -> AttestResult,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current.applicationContext
    val sim = remember { SimReader(ctx) }
    val formatter = remember { PhoneFormatter(ctx) }

    val simRaw = remember(sim) { sim.read() }
    val country = remember(sim) { sim.networkCountryIso() }
    val simE164 = remember(simRaw, country) { simRaw?.let { formatter.normalize(it, country) } }
    val phonesMatch = simE164 == null || simE164 == inbound.phoneE164

    var phase by remember { mutableStateOf<Phase>(Phase.Confirm(phonesMatch)) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Phone: ${formatter.formatForDisplay(inbound.phoneE164)}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Wallet: ${inbound.wallet.base58().take(8)}…${inbound.wallet.base58().takeLast(4)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (simE164 == null) {
                Text(
                    "(Could not read this device's SIM. If you proceed, the " +
                        "attestation will still bind the laptop's wallet to the " +
                        "phone number above — make sure that number is yours.)",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (!phonesMatch) {
                Text(
                    "⚠ This device's SIM is " +
                        formatter.formatForDisplay(simE164) +
                        ", which doesn't match the laptop's request. " +
                        "Only proceed if you actually own the requested number.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    when (val current = phase) {
        is Phase.Confirm -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { phase = Phase.Running }) {
                    Text(if (current.matches) "Generate attestation" else "Generate anyway")
                }
                OutlinedButton(onClick = onClose) { Text("Cancel") }
            }
        }
        Phase.Running -> {
            Text("Generating hardware attestation…")
            LaunchedEffect(inbound.phoneE164, inbound.wallet) {
                phase = when (val r = runAttestation(inbound)) {
                    is AttestResult.Ok -> Phase.Done(r.hashBase58, r.returnUri)
                    is AttestResult.Error -> Phase.Failed(r.message)
                }
            }
        }
        is Phase.Done -> {
            DonePanel(returnUri = current.returnUri, hashBase58 = current.hashBase58, onClose = onClose)
        }
        is Phase.Failed -> {
            Text(
                "Attestation failed: ${current.message}",
                color = MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { phase = Phase.Running }) { Text("Retry") }
                OutlinedButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}

@Composable
private fun DonePanel(returnUri: String, hashBase58: String, onClose: () -> Unit) {
    val qrBitmap = remember(returnUri) { InviteQr.render(returnUri, sizePx = 720) }

    Text(
        "Show this QR to your laptop's camera, or copy the hash below into the " +
            "register portal manually.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(8.dp))
    Image(
        bitmap = qrBitmap.asImageBitmap(),
        contentDescription = "Return QR",
        modifier = Modifier.size(280.dp),
    )
    Spacer(Modifier.height(8.dp))
    Text("Hash (base58):", style = MaterialTheme.typography.titleSmall)
    Text(
        hashBase58,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Start,
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = onClose) { Text("Done") }
}

private sealed interface Phase {
    data class Confirm(val matches: Boolean) : Phase
    data object Running : Phase
    data class Done(val hashBase58: String, val returnUri: String) : Phase
    data class Failed(val message: String) : Phase
}
