package com.overcall.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.overcall.BuildConfig
import com.overcall.call.PhoneFormatter
import com.overcall.call.SimReader
import com.overcall.device.DeviceProfile
import com.overcall.pay.MwaSigner
import com.overcall.pay.RegisterFlow
import com.overcall.pay.RevokeFlow
import com.overcall.pay.WalletHolder
import com.overcall.pay.WalletInfo
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.overcall.prefs.AppPrefs
import com.overcall.registry.PhoneRecord
import com.overcall.registry.RegistryClient
import com.overcall.registry.SolanaRpc
import com.overcall.service.OverCallForegroundService
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val mwa = MwaSigner()
    private val rpc by lazy { SolanaRpc(BuildConfig.RPC_URL) }
    private val registry by lazy { RegistryClient(rpc) }
    private val registerFlow by lazy { RegisterFlow(rpc, mwa, registry) }
    private val revokeFlow by lazy { RevokeFlow(rpc, mwa) }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    /**
     * Single launcher for the "I want to detect calls" multi-perm grant.
     * Starts CallWatcher again on grant — it no-ops if perms still missing,
     * subscribes to telephony state if granted. The user's choice
     * sticks across launches; we re-check perms in onResume.
     */
    private val callDetectionPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            (application as com.overcall.OverCallApp).callWatcher.start()
        }

    fun requestCallDetectionPermissions() {
        callDetectionPermissionLauncher.launch(
            arrayOf(
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.READ_CALL_LOG,
            ),
        )
    }

    // Constructed lazily but BEFORE the activity is started: AndroidX
    // Lifecycle requires every ActivityResultLauncher (which is what
    // ActivityResultSender registers internally) to exist before STARTED.
    // Constructing it inside a click handler crashes with "Lifecycle owners
    // must register before they are started." Initialized in `onCreate`.
    private lateinit var resultSender: ActivityResultSender

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resultSender = ActivityResultSender(this)
        maybeRequestNotificationPermission()
        setContent {
            MaterialTheme {
                Root(
                    activity = this,
                    mwa = mwa,
                    resultSender = resultSender,
                    registry = registry,
                    registerFlow = registerFlow,
                    revokeFlow = revokeFlow,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as com.overcall.OverCallApp).callWatcher.start()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val perm = "android.permission.POST_NOTIFICATIONS"
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(perm)
        }
    }
}

private const val TAG = "OverCall/MainActivity"

private enum class Tab { Home, Settings }
private enum class FullScreen { None, Register }

@Composable
private fun Root(
    activity: ComponentActivity,
    mwa: MwaSigner,
    resultSender: ActivityResultSender,
    registry: RegistryClient,
    registerFlow: RegisterFlow,
    revokeFlow: RevokeFlow,
) {
    val ctx = LocalContext.current.applicationContext
    val prefs = remember { AppPrefs(ctx) }
    var profile by remember { mutableStateOf(DeviceProfile.detect(ctx)) }
    val sim = remember { SimReader(ctx) }
    val formatter = remember { PhoneFormatter(ctx) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-detect every time the activity returns from background. This catches
    // permission grants made in Settings (overlay) and via the perm dialogs
    // (READ_PHONE_STATE / READ_CALL_LOG) without forcing the user to relaunch.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                profile = DeviceProfile.detect(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var tab by remember { mutableStateOf(Tab.Home) }
    var fullScreen by remember { mutableStateOf(FullScreen.None) }
    var wallet by remember { mutableStateOf<WalletInfo?>(null) }
    var registration by remember { mutableStateOf<PhoneRecord?>(null) }
    var lastSig by remember { mutableStateOf<String?>(null) }
    var firstLaunchPromptOpen by remember { mutableStateOf(false) }

    suspend fun onWalletConnected(info: WalletInfo?) {
        wallet = info
        WalletHolder.set(info)
        if (info == null) {
            registration = null
            return
        }
        registration = try {
            registry.lookupByOwner(info.pubkey)
        } catch (t: Throwable) {
            Log.w(TAG, "lookupByOwner failed on connect", t)
            null
        }
        if (
            profile.isSeeker &&
            registration == null &&
            !prefs.firstRegisterPromptShown
        ) {
            firstLaunchPromptOpen = true
        }
    }

    // Wait for the just-submitted register tx to propagate through the RPC.
    // Even after fakewallet returns "submitted" the read-side may briefly
    // not see the new account: load-balanced endpoints, slot lag, or just
    // confirmed-vs-finalized. Poll lookupByOwner with backoff for ~30 s.
    suspend fun pollRegistrationAfterRegister(owner: com.solana.publickey.SolanaPublicKey) {
        // Delays before each attempt: 1, 2, 4, 4, 4, 4, 4, 4 s ≈ 27 s total.
        val delays = longArrayOf(1_000, 2_000, 4_000, 4_000, 4_000, 4_000, 4_000, 4_000)
        for (d in delays) {
            delay(d)
            val r = try {
                registry.lookupByOwner(owner)
            } catch (t: Throwable) {
                Log.w(TAG, "lookupByOwner failed during post-register poll", t)
                null
            }
            if (r != null) {
                registration = r
                return
            }
        }
        Log.w(TAG, "post-register lookup gave up after ~27s; on-chain tx may have failed")
    }

    if (fullScreen == FullScreen.Register && wallet != null) {
        RegisterScreen(
            activity = activity,
            resultSender = resultSender,
            owner = wallet!!.pubkey,
            registerFlow = registerFlow,
            onDone = { sig ->
                lastSig = sig
                fullScreen = FullScreen.None
                val owner = wallet!!.pubkey
                scope.launch { pollRegistrationAfterRegister(owner) }
            },
            onBack = { fullScreen = FullScreen.None },
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.Home,
                    onClick = { tab = Tab.Home },
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                )
                NavigationBarItem(
                    selected = tab == Tab.Settings,
                    onClick = { tab = Tab.Settings },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.Home -> HomeScreen(
                    activity = activity,
                    mwa = mwa,
                    resultSender = resultSender,
                    profile = profile,
                    wallet = wallet,
                    registration = registration,
                    lastSig = lastSig,
                    onConnected = { info -> scope.launch { onWalletConnected(info) } },
                    onDisconnect = {
                        mwa.disconnect()
                        scope.launch { onWalletConnected(null) }
                        lastSig = null
                    },
                    onRegisterClick = { fullScreen = FullScreen.Register },
                )
                Tab.Settings -> SettingsScreen(
                    activity = activity,
                    resultSender = resultSender,
                    wallet = wallet,
                    registration = registration,
                    revokeFlow = revokeFlow,
                    onRegisterClick = { fullScreen = FullScreen.Register },
                    onRevoked = { registration = null },
                )
            }
        }
    }

    if (firstLaunchPromptOpen && wallet != null) {
        FirstLaunchPrompt(
            seekerSimE164 = sim.read()?.let { formatter.normalize(it, sim.networkCountryIso()) },
            displayPhone = sim.read()?.let { formatter.formatForDisplay(it) },
            onDismiss = {
                firstLaunchPromptOpen = false
                prefs.firstRegisterPromptShown = true
            },
            onConfirm = {
                firstLaunchPromptOpen = false
                prefs.firstRegisterPromptShown = true
                fullScreen = FullScreen.Register
            },
        )
    }
}

@Composable
private fun FirstLaunchPrompt(
    seekerSimE164: String?,
    displayPhone: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Welcome to OverCall") },
        text = {
            Column {
                Text("This is a Seeker phone — you can register your number now to start receiving payments by phone.")
                Spacer(Modifier.height(8.dp))
                if (seekerSimE164 != null) {
                    Text(
                        "Detected SIM: ${displayPhone ?: seekerSimE164}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        "Couldn't read SIM number — you'll enter it manually next.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Registration costs ~0.001 SOL on devnet plus a one-time wallet signature for hardware attestation.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Register now") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Not now") } },
    )
}

@Composable
private fun HomeScreen(
    activity: ComponentActivity,
    mwa: MwaSigner,
    resultSender: ActivityResultSender,
    profile: DeviceProfile,
    wallet: WalletInfo?,
    registration: PhoneRecord?,
    lastSig: String?,
    onConnected: (WalletInfo?) -> Unit,
    onDisconnect: () -> Unit,
    onRegisterClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("OverCall (${BuildConfig.CLUSTER})", style = MaterialTheme.typography.titleLarge)
        Text("RPC: ${BuildConfig.RPC_URL}")

        Spacer(Modifier.height(8.dp))
        Text("Device profile:", style = MaterialTheme.typography.titleMedium)
        Text("• Seeker: ${profile.isSeeker}")
        Text("• Seed Vault: ${profile.hasSeedVaultService}")
        Text("• Telephony: ${profile.hasTelephony}")

        Spacer(Modifier.height(8.dp))
        Text("Call detection:", style = MaterialTheme.typography.titleMedium)
        Text("Required so the pay bubble pops up automatically when a call starts.",
            style = MaterialTheme.typography.bodySmall)
        fun mark(b: Boolean) = if (b) "✓" else "✗"
        Text("$ {mark(profile.canDrawOverlays)} Draw over other apps")
        Text("$ {mark(profile.canReadPhoneState)} Read phone state")
        Text("$ {mark(profile.canReadCallLog)} Read call log (other party's number)")

        if (!profile.canReadPhoneState || !profile.canReadCallLog) {
            Button(onClick = {
                val act = activity as? MainActivity
                act?.requestCallDetectionPermissions()
            }) { Text("Grant phone permissions") }
        }
        if (!profile.canDrawOverlays) {
            Button(onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}"),
                )
                activity.startActivity(intent)
            }) { Text("Grant overlay permission") }
        }
        if (profile.callDetectionReady) {
            Text("✓ Bubble will auto-pop on incoming and outgoing calls.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(16.dp))
        Text("Wallet:", style = MaterialTheme.typography.titleMedium)

        if (wallet == null && profile.isSeeker && profile.hasSeedVaultService) {
            Text(
                "💡 On Seeker, the bundled Seed Vault wallet signs in hardware. " +
                    "Set it as your default Solana wallet before tapping Connect.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        when {
            connecting -> Text("Connecting…")
            wallet != null -> {
                Text("Connected: ${wallet.pubkey.base58().take(8)}…")
                if (wallet.hardwareBacked) {
                    Text(
                        "🔒 Hardware-backed (${wallet.displayLabel})",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (profile.isSeeker) {
                    Text(
                        "⚠ Hot wallet (${wallet.displayLabel}). " +
                            "Switch your default to Seed Vault for hardware-backed signing.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (registration != null) {
                    Text("📞 Registered.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("No phone registered.", style = MaterialTheme.typography.bodySmall)
                }
            }
            error != null -> Text("Error: $error", color = MaterialTheme.colorScheme.error)
            else -> Text("Not connected")
        }

        Button(
            enabled = !connecting,
            onClick = {
                error = null
                connecting = true
                scope.launch {
                    try {
                        val info = mwa.connect(resultSender)
                        onConnected(info)
                        if (info == null) {
                            error = "No wallet returned (cancelled or no wallet installed)"
                        }
                    } catch (t: Throwable) {
                        error = t.message ?: t::class.simpleName
                    } finally {
                        connecting = false
                    }
                }
            },
        ) { Text(if (wallet != null) "Reconnect Wallet" else "Connect Wallet") }

        if (wallet != null) {
            Button(onClick = onRegisterClick) {
                Text(if (registration == null) "Register Phone" else "Re-register Phone")
            }
            Button(onClick = {
                val intent = Intent(activity, OverCallForegroundService::class.java).apply {
                    action = OverCallForegroundService.ACTION_REOPEN
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.startForegroundService(intent)
                } else {
                    activity.startService(intent)
                }
            }) { Text("Re-attach Bubble") }

            TextButton(onClick = onDisconnect) { Text("Disconnect Wallet") }
        }

        lastSig?.let {
            Spacer(Modifier.height(8.dp))
            Text("Last tx: ${it.take(12)}…")
        }
    }
}
