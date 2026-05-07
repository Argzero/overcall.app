package com.overcall.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.overcall.BuildConfig
import com.overcall.call.PhoneFormatter
import com.overcall.invite.InviteLink
import com.overcall.pay.MwaSigner
import com.overcall.pay.PayFlow
import com.overcall.pay.WalletHolder
import com.overcall.registry.PhoneRecord
import com.overcall.registry.RegistryClient
import com.overcall.registry.SolanaRpc
import com.overcall.util.Base58
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * The expand-on-tap pay panel. Hosted as a transparent Activity (not the
 * overlay window) for two reasons:
 *  1. MWA needs an Activity context for ActivityResultSender.
 *  2. Text input from a TYPE_APPLICATION_OVERLAY view doesn't reliably
 *     summon the soft keyboard across OEMs.
 *
 * Launched from OverlayController.onTap with EXTRA_PHONE set to the called
 * party's E.164. Uses the connected wallet from WalletHolder; if none,
 * tells the user to open OverCall first and connect a wallet.
 */
class PanelActivity : ComponentActivity() {

    private val mwa = MwaSigner()
    private val rpc by lazy { SolanaRpc(BuildConfig.RPC_URL) }
    private val registry by lazy { RegistryClient(rpc) }
    private val payFlow by lazy { PayFlow(rpc, mwa, registry) }

    // Same lifecycle constraint as MainActivity — register the launcher
    // before STARTED, not lazily inside a click handler.
    private lateinit var resultSender: ActivityResultSender

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resultSender = ActivityResultSender(this)
        val phoneE164 = intent.getStringExtra(EXTRA_PHONE)
        if (phoneE164 == null) {
            finish(); return
        }

        // Auto-close when the active call ends. Without this the user could
        // pay post-hoc — open the bubble during a call, hang up, then send
        // money to the (no-longer-on-the-line) caller. Spec calls for the
        // payment screen to close on hangup.
        val app = application as com.overcall.OverCallApp
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                app.activeCallPhone.collect { active ->
                    if (active == null) {
                        // Call ended (or never was). Close this panel.
                        finish()
                    }
                }
            }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        PanelCard(
                            activity = this@PanelActivity,
                            resultSender = resultSender,
                            payFlow = payFlow,
                            phoneE164 = phoneE164,
                            onClose = { finish() },
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_PHONE = "phone_e164"

        fun newIntent(context: Context, phoneE164: String): Intent =
            Intent(context, PanelActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
                putExtra(EXTRA_PHONE, phoneE164)
            }
    }
}

private sealed interface PanelState {
    data object Resolving : PanelState
    data class Ready(val record: PhoneRecord) : PanelState
    data class Unregistered(val phoneE164: String) : PanelState
    data object Sending : PanelState
    data class Sent(val signatureBase58: String) : PanelState
    data class Failed(val message: String) : PanelState
}

@Composable
private fun PanelCard(
    activity: ComponentActivity,
    resultSender: ActivityResultSender,
    payFlow: PayFlow,
    phoneE164: String,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current.applicationContext
    val formatter = remember { PhoneFormatter(ctx) }
    val scope = rememberCoroutineScope()
    val displayPhone = remember(phoneE164) { formatter.formatForDisplay(phoneE164) }
    val sender = WalletHolder.pubkey()

    var amountSol by remember { mutableStateOf("0.01") }
    var state by remember { mutableStateOf<PanelState>(PanelState.Resolving) }

    LaunchedEffect(phoneE164) {
        try {
            val record = payFlow.resolve(phoneE164)
            state = if (record == null) PanelState.Unregistered(phoneE164)
                    else PanelState.Ready(record)
        } catch (t: Throwable) {
            state = PanelState.Failed("Resolve failed: ${t.message ?: t::class.simpleName}")
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Pay $displayPhone", style = MaterialTheme.typography.titleLarge)

            when (val s = state) {
                PanelState.Resolving -> Text("Looking up recipient on devnet…")

                is PanelState.Ready -> {
                    // The resolver pulled the registered wallet for the
                    // other caller's E.164 via phone-registry's reverse
                    // index — no QR needed because both parties are on
                    // the call already.
                    Text("→ ${s.record.owner.base58().take(8)}…",
                        style = MaterialTheme.typography.bodyMedium)
                    if (sender == null) {
                        Text("No wallet connected. Open OverCall and tap Connect Wallet first.")
                    } else {
                        OutlinedTextField(
                            value = amountSol,
                            onValueChange = { amountSol = it },
                            label = { Text("Amount (SOL)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Network fee: 0.01% (added on top)",
                            style = MaterialTheme.typography.bodySmall)

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                enabled = sender != null && amountSol.toDoubleOrNull() != null,
                                onClick = {
                                    val amt = (amountSol.toDouble() * 1_000_000_000).toLong()
                                    state = PanelState.Sending
                                    scope.launch {
                                        val res = payFlow.paySol(
                                            resultSender = resultSender,
                                            sender = sender,
                                            phoneE164 = phoneE164,
                                            amountLamports = amt,
                                        )
                                        state = when (res) {
                                            is PayFlow.Result.Success -> PanelState.Sent(
                                                base58Sig(res.signature),
                                            )
                                            is PayFlow.Result.NotRegistered -> PanelState.Unregistered(res.phoneE164)
                                            is PayFlow.Result.Failure -> PanelState.Failed(res.message)
                                        }
                                    }
                                },
                            ) { Text("Send  ➤") }
                            OutlinedButton(onClick = onClose) { Text("✕  Close") }
                        }
                    }
                }

                is PanelState.Unregistered -> {
                    UnregisteredCard(
                        displayPhone = displayPhone,
                        inviter = sender,
                        activity = activity,
                    )
                }

                PanelState.Sending -> Text("Submitting on devnet…")

                is PanelState.Sent -> {
                    Text("Sent.", style = MaterialTheme.typography.titleMedium)
                    Text("Tx: ${s.signatureBase58.take(12)}…")
                }

                is PanelState.Failed -> {
                    Text("Failed", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error)
                    Text(s.message)
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}

private fun base58Sig(bytes: ByteArray): String = Base58.encode(bytes)

/**
 * Shown when the resolver returns null for the other caller's E.164 — the
 * person on the other end of the call doesn't have OverCall set up.
 *
 * No QR here on purpose: both parties are on a phone call together, neither
 * can scan the other's screen. The path forward is text — share the invite
 * link via SMS / messenger / whatever side channel they have, including
 * just reading the URL aloud over the call. The receiver opens the link,
 * installs OverCall, registers their phone — then a re-resolve in this
 * panel surfaces them as Ready and the user can send.
 */
@Composable
private fun UnregisteredCard(
    displayPhone: String,
    inviter: com.solana.publickey.SolanaPublicKey?,
    activity: ComponentActivity,
) {
    Text("$displayPhone — not on OverCall")
    Spacer(Modifier.height(4.dp))
    Text(
        "Send them this link so they can install OverCall and register " +
            "their phone. After they finish, tap their bubble again.",
        style = MaterialTheme.typography.bodyMedium,
    )

    if (inviter == null) {
        Text("Connect a wallet first to generate an invite link.")
        return
    }

    val link = remember(inviter) { InviteLink.forInviter(inviter) }
    val ctx = LocalContext.current

    Spacer(Modifier.height(8.dp))
    Text(link, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Join me on OverCall and we can pay each other while we talk: $link",
                )
            }
            activity.startActivity(Intent.createChooser(intent, "Share OverCall invite"))
        }) { Text("Share") }

        Button(onClick = {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("OverCall invite", link))
        }) { Text("Copy") }
    }
}
