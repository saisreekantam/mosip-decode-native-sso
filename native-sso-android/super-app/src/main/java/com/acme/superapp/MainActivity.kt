package com.acme.superapp

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.acme.sso.core.BuildConfig
import com.acme.sso.core.DevProvisioning
import com.acme.sso.core.Jose
import com.acme.sso.core.LoginLauncher
import com.acme.sso.ui.AcmeBrand
import com.acme.sso.ui.AcmeTheme
import com.acme.sso.ui.AcmeTopBar
import com.acme.sso.ui.BusyRow
import com.acme.sso.ui.DeveloperDetails
import com.acme.sso.ui.Mono
import com.acme.sso.ui.SuccessTick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val log = mutableStateListOf<String>()
    private val busy = mutableStateOf<String?>(null)
    private val error = mutableStateOf<String?>(null)
    private val refresh = mutableStateOf(0)
    private val demoUin = mutableStateOf<String?>(null)
    private val showApps = mutableStateOf(false)
    private val confirmLogout = mutableStateOf(false)
    private val confirmSignOutApp = mutableStateOf<ConnectedApp?>(null)
    private val developer = mutableStateOf(false)
    private lateinit var session: SuperAppSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = SuperAppSession.get(this)
        setContent { AcmeTheme(AcmeBrand.SuperApp) { if (showApps.value) ConnectedAppsScreen() else HomeScreen() } }
        run("Registering with eSignet", silent = true) { session.ensureRegistered() }
        handleRedirect(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh.value++ // sibling apps may have used the sign-in meanwhile
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    private fun handleRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "com.acme.superapp") return
        run("Completing eSignet login") { session.completeLogin(data) }
    }

    /** Runs [block] off the main thread; [silent] keeps the spinner hidden for background work. */
    private fun run(label: String, silent: Boolean = false, block: suspend () -> String) {
        if (!silent) busy.value = label
        error.value = null
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { block() } }
            log.add(0, if (result.isSuccess) "✓ $label: ${result.getOrNull()}" else "✗ $label: ${result.exceptionOrNull()?.message}")
            if (result.isFailure && !silent) error.value = "$label failed. ${result.exceptionOrNull()?.message.orEmpty().take(140)}"
            if (!silent) busy.value = null
            refresh.value++
        }
    }

    @Composable
    private fun HomeScreen() {
        refresh.value
        Scaffold(topBar = { AcmeTopBar("Acme Health", "Super App") }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (session.signedIn) SignedInCard() else SignedOutCard()
                busy.value?.let { BusyRow("$it…") }
                error.value?.let {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                DeveloperDetails(developer.value, { developer.value = it }) { TechnicalDetails() }
            }
        }
        if (confirmLogout.value) {
            AlertDialog(
                onDismissRequest = { confirmLogout.value = false },
                title = { Text("Log out of all Acme apps?") },
                text = {
                    val apps = session.connectedApps.filter { it.connected }.map { it.label }
                    Text("You will be signed out here" + (if (apps.isEmpty()) "." else " and in ${apps.joinToString()}.") +
                        " Apps that are open lose access right away. You will need your OTP to sign in again.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmLogout.value = false
                        run("Logging out of all Acme apps") { session.logoutEverywhere() }
                    }) { Text("Log out") }
                },
                dismissButton = { TextButton(onClick = { confirmLogout.value = false }) { Text("Cancel") } },
            )
        }
    }

    @Composable
    private fun SignedInCard() {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SuccessTick(44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("You're signed in with your Acme account", fontWeight = FontWeight.SemiBold)
                        session.displayName?.let { Text("Hi, $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                Text("Other Acme apps on this phone can now sign you in with one tap, without an OTP.",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        val apps = session.connectedApps
        Card(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open connected apps") { showApps.value = true },
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Connected apps", fontWeight = FontWeight.SemiBold)
                    Text(
                        when (val n = apps.count { it.connected }) {
                            0 -> "No other Acme app uses your sign-in yet"
                            1 -> "1 app uses your sign-in: ${apps.first { it.connected }.label}"
                            else -> "$n apps use your sign-in"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
        OutlinedButton(onClick = { confirmLogout.value = true }, enabled = busy.value == null) { Text("Log out of all Acme apps") }
    }

    @Composable
    private fun SignedOutCard() {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Not signed in", fontWeight = FontWeight.SemiBold)
                }
                Text("Sign in once with eSignet, and every Acme app on this phone can sign you in with one tap.",
                    style = MaterialTheme.typography.bodyMedium)
                demoUin.value?.let { Text("Demo login: UIN $it, OTP 111111", style = MaterialTheme.typography.bodyMedium) }
                OutlinedButton(onClick = {
                    run("Creating demo identity") {
                        // One fixed, easy-to-type demo identity; reused when it already exists.
                        val uin = DEMO_UIN
                        val res = DevProvisioning.createMockIdentity(MOCK_IDENTITY_URL, uin, "Asha Devi")
                        val exists = res.body.contains("duplicate_individual_id")
                        check(res.status == 200 && (!res.body.contains("errorCode") || exists)) { res.body }
                        demoUin.value = uin
                        (if (exists) "using existing" else "created") + " UIN $uin (OTP 111111)"
                    }
                }, enabled = busy.value == null) { Text("Create demo user") }
                Button(onClick = {
                    run("Starting eSignet login (PAR + DPoP)") {
                        val pending = session.startLogin()
                        withContext(Dispatchers.Main) {
                            LoginLauncher.open(this@MainActivity, pending.authorizeUrl, SuperAppSession.REDIRECT_URI)
                        }
                        "opened the eSignet login UI"
                    }
                }, enabled = busy.value == null) { Text("Sign in with eSignet") }
            }
        }
    }

    @Composable
    private fun TechnicalDetails() {
        Text("Device key (key A)", fontWeight = FontWeight.SemiBold)
        Mono("jkt ${session.deviceKey.thumbprint}")
        Mono(if (session.deviceKey.strongBox) "StrongBox secure element" else "Android Keystore (TEE)")
        Text("Client", fontWeight = FontWeight.SemiBold)
        Mono(session.clientId)
        Mono("eSignet ${BuildConfig.ESIGNET_ISSUER}")
        session.idToken?.let { idToken ->
            val claims = Jose.payload(idToken)
            Text("SSO session", fontWeight = FontWeight.SemiBold)
            Mono("sid ${claims.optString("sid")}")
            Mono("ds_hash ${claims.optString("ds_hash")}")
            Mono("device_secret held in encrypted storage")
            Mono("allow-list ${session.siblings.ifEmpty { listOf("none") }.joinToString()}")
        }
        if (log.isNotEmpty()) {
            Text("Activity", fontWeight = FontWeight.SemiBold)
            log.forEach { Mono(it) }
        }
    }

    @Composable
    private fun ConnectedAppsScreen() {
        refresh.value
        BackHandler { showApps.value = false }
        Scaffold(topBar = { AcmeTopBar("Connected apps", onBack = { showApps.value = false }) }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("These Acme apps sign you in with your Acme account. They only get the details you agreed to share.",
                    style = MaterialTheme.typography.bodyMedium)
                val apps = session.connectedApps
                if (apps.isEmpty()) {
                    Text("No other Acme app has used your sign-in yet.", style = MaterialTheme.typography.bodyLarge)
                }
                apps.forEach { app -> AppRow(app) }
                if (apps.isNotEmpty()) {
                    Text("Signing an app out stops it right away: it needs this app to vouch for every request it makes. " +
                        "It can join again when you choose \"Continue with your Acme account\" in it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { confirmLogout.value = true }, enabled = busy.value == null && session.signedIn) {
                    Text("Log out of all Acme apps")
                }
            }
        }
        confirmSignOutApp.value?.let { app ->
            AlertDialog(
                onDismissRequest = { confirmSignOutApp.value = null },
                title = { Text("Sign out ${app.label}?") },
                text = { Text("${app.label} will stop using your Acme sign-in. Other apps stay signed in.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmSignOutApp.value = null
                        run("Signing out ${app.label}") { session.signOutApp(app.packageName) }
                    }) { Text("Sign out") }
                },
                dismissButton = { TextButton(onClick = { confirmSignOutApp.value = null }) { Text("Cancel") } },
            )
        }
        if (confirmLogout.value) {
            AlertDialog(
                onDismissRequest = { confirmLogout.value = false },
                title = { Text("Log out of all Acme apps?") },
                text = { Text("You will be signed out here and in every connected app. You will need your OTP to sign in again.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmLogout.value = false
                        showApps.value = false
                        run("Logging out of all Acme apps") { session.logoutEverywhere() }
                    }) { Text("Log out") }
                },
                dismissButton = { TextButton(onClick = { confirmLogout.value = false }) { Text("Cancel") } },
            )
        }
    }

    @Composable
    private fun AppRow(app: ConnectedApp) {
        val used = if (app.lastUsed == 0L) "Not used yet" else "Last used " +
            DateUtils.getRelativeTimeSpanString(app.lastUsed, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp).semantics(mergeDescendants = true) {
                contentDescription = "${app.label}, ${if (app.connected) "connected" else "signed out"}, $used"
            }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(app.label, fontWeight = FontWeight.SemiBold)
                    Text(if (app.connected) "Connected · $used" else "Signed out · $used", style = MaterialTheme.typography.bodySmall)
                    if (developer.value && app.clientId.isNotEmpty()) Mono(app.clientId)
                }
                if (app.connected) {
                    OutlinedButton(onClick = { confirmSignOutApp.value = app }, enabled = busy.value == null) { Text("Sign out") }
                }
            }
        }
    }

    companion object {
        private const val MOCK_IDENTITY_URL = "http://localhost:8082"
        private const val DEMO_UIN = "1234567890"
    }
}
