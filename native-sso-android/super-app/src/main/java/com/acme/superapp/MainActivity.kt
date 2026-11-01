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
import androidx.compose.ui.res.stringResource
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
        run(getString(R.string.task_registering), silent = true) { session.ensureRegistered() }
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
        run(getString(R.string.task_completing_login)) { session.completeLogin(data) }
    }

    /** Runs [block] off the main thread; [silent] keeps the spinner hidden for background work. */
    private fun run(label: String, silent: Boolean = false, block: suspend () -> String) {
        if (!silent) busy.value = label
        error.value = null
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { block() } }
            log.add(0, if (result.isSuccess) "✓ $label: ${result.getOrNull()}" else "✗ $label: ${result.exceptionOrNull()?.message}")
            if (result.isFailure && !silent) error.value = getString(R.string.task_failed, label, result.exceptionOrNull()?.message.orEmpty().take(140))
            if (!silent) busy.value = null
            refresh.value++
        }
    }

    @Composable
    private fun HomeScreen() {
        refresh.value
        Scaffold(topBar = { AcmeTopBar(stringResource(R.string.brand_title), stringResource(R.string.brand_subtitle)) }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (session.signedIn) SignedInCard() else SignedOutCard()
                busy.value?.let { BusyRow(stringResource(R.string.busy_suffix, it)) }
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
                title = { Text(stringResource(R.string.logout_title)) },
                text = {
                    val apps = session.connectedApps.filter { it.connected }.map { it.label }
                    Text(if (apps.isEmpty()) stringResource(R.string.logout_body_alone)
                        else stringResource(R.string.logout_body_with_apps, apps.joinToString()))
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmLogout.value = false
                        run(getString(R.string.task_logging_out)) { session.logoutEverywhere() }
                    }) { Text(stringResource(R.string.log_out)) }
                },
                dismissButton = { TextButton(onClick = { confirmLogout.value = false }) { Text(stringResource(R.string.cancel)) } },
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
                        Text(stringResource(R.string.signed_in_title), fontWeight = FontWeight.SemiBold)
                        session.displayName?.let { Text(stringResource(R.string.greeting, it), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                Text(stringResource(R.string.signed_in_hint),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        val apps = session.connectedApps
        val openApps = stringResource(R.string.open_connected_apps)
        Card(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = openApps) { showApps.value = true },
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.connected_apps), fontWeight = FontWeight.SemiBold)
                    Text(
                        when (val n = apps.count { it.connected }) {
                            0 -> stringResource(R.string.connected_none)
                            1 -> stringResource(R.string.connected_one, apps.first { it.connected }.label)
                            else -> stringResource(R.string.connected_many, n)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
        OutlinedButton(onClick = { confirmLogout.value = true }, enabled = busy.value == null) { Text(stringResource(R.string.log_out_all_button)) }
    }

    @Composable
    private fun SignedOutCard() {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.not_signed_in), fontWeight = FontWeight.SemiBold)
                }
                Text(stringResource(R.string.signed_out_hint),
                    style = MaterialTheme.typography.bodyMedium)
                demoUin.value?.let { Text(stringResource(R.string.demo_login, it), style = MaterialTheme.typography.bodyMedium) }
                OutlinedButton(onClick = {
                    val creating = getString(R.string.task_creating_demo)
                    run(creating) {
                        // One fixed, easy-to-type demo identity; reused when it already exists.
                        val uin = DEMO_UIN
                        val res = DevProvisioning.createMockIdentity(MOCK_IDENTITY_URL, uin, "Asha Devi")
                        val exists = res.body.contains("duplicate_individual_id")
                        check(res.status == 200 && (!res.body.contains("errorCode") || exists)) { res.body }
                        demoUin.value = uin
                        getString(if (exists) R.string.demo_using_existing else R.string.demo_created, uin)
                    }
                }, enabled = busy.value == null) { Text(stringResource(R.string.create_demo_user)) }
                Button(onClick = {
                    run(getString(R.string.task_starting_login)) {
                        val pending = session.startLogin()
                        withContext(Dispatchers.Main) {
                            LoginLauncher.open(this@MainActivity, pending.authorizeUrl, SuperAppSession.REDIRECT_URI)
                        }
                        getString(R.string.opened_login_ui)
                    }
                }, enabled = busy.value == null) { Text(stringResource(R.string.sign_in_with_esignet)) }
            }
        }
    }

    @Composable
    private fun TechnicalDetails() {
        Text(stringResource(R.string.tech_device_key), fontWeight = FontWeight.SemiBold)
        Mono("jkt ${session.deviceKey.thumbprint}")
        Mono(stringResource(if (session.deviceKey.strongBox) R.string.tech_strongbox else R.string.tech_keystore))
        Text(stringResource(R.string.tech_client), fontWeight = FontWeight.SemiBold)
        Mono(session.clientId)
        Mono("eSignet ${BuildConfig.ESIGNET_ISSUER}")
        session.idToken?.let { idToken ->
            val claims = Jose.payload(idToken)
            Text(stringResource(R.string.tech_sso_session), fontWeight = FontWeight.SemiBold)
            Mono("sid ${claims.optString("sid")}")
            Mono("ds_hash ${claims.optString("ds_hash")}")
            Mono(stringResource(R.string.tech_device_secret))
            Mono("allow-list ${session.siblings.ifEmpty { listOf(stringResource(R.string.tech_none)) }.joinToString()}")
        }
        if (log.isNotEmpty()) {
            Text(stringResource(R.string.tech_activity), fontWeight = FontWeight.SemiBold)
            log.forEach { Mono(it) }
        }
    }

    @Composable
    private fun ConnectedAppsScreen() {
        refresh.value
        BackHandler { showApps.value = false }
        Scaffold(topBar = { AcmeTopBar(stringResource(R.string.connected_apps_title), onBack = { showApps.value = false }) }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.connected_intro),
                    style = MaterialTheme.typography.bodyMedium)
                val apps = session.connectedApps
                if (apps.isEmpty()) {
                    Text(stringResource(R.string.connected_empty), style = MaterialTheme.typography.bodyLarge)
                }
                apps.forEach { app -> AppRow(app) }
                if (apps.isNotEmpty()) {
                    Text(stringResource(R.string.connected_signout_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { confirmLogout.value = true }, enabled = busy.value == null && session.signedIn) {
                    Text(stringResource(R.string.log_out_all_button))
                }
            }
        }
        confirmSignOutApp.value?.let { app ->
            AlertDialog(
                onDismissRequest = { confirmSignOutApp.value = null },
                title = { Text(stringResource(R.string.sign_out_app_title, app.label)) },
                text = { Text(stringResource(R.string.sign_out_app_body, app.label)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmSignOutApp.value = null
                        run(getString(R.string.task_signing_out_app, app.label)) { session.signOutApp(app.packageName) }
                    }) { Text(stringResource(R.string.sign_out)) }
                },
                dismissButton = { TextButton(onClick = { confirmSignOutApp.value = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        if (confirmLogout.value) {
            AlertDialog(
                onDismissRequest = { confirmLogout.value = false },
                title = { Text(stringResource(R.string.logout_title)) },
                text = { Text(stringResource(R.string.logout_body_all)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmLogout.value = false
                        showApps.value = false
                        run(getString(R.string.task_logging_out)) { session.logoutEverywhere() }
                    }) { Text(stringResource(R.string.log_out)) }
                },
                dismissButton = { TextButton(onClick = { confirmLogout.value = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }

    @Composable
    private fun AppRow(app: ConnectedApp) {
        val used = if (app.lastUsed == 0L) stringResource(R.string.not_used_yet) else stringResource(R.string.last_used,
            DateUtils.getRelativeTimeSpanString(app.lastUsed, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
        val state = stringResource(if (app.connected) R.string.state_connected else R.string.state_signed_out)
        val a11y = stringResource(R.string.app_a11y, app.label, state, used)
        val rowText = stringResource(if (app.connected) R.string.app_row_connected else R.string.app_row_signed_out, used)
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp).semantics(mergeDescendants = true) {
                contentDescription = a11y
            }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(app.label, fontWeight = FontWeight.SemiBold)
                    Text(rowText, style = MaterialTheme.typography.bodySmall)
                    if (developer.value && app.clientId.isNotEmpty()) Mono(app.clientId)
                }
                if (app.connected) {
                    OutlinedButton(onClick = { confirmSignOutApp.value = app }, enabled = busy.value == null) { Text(stringResource(R.string.sign_out)) }
                }
            }
        }
    }

    companion object {
        private const val MOCK_IDENTITY_URL = "http://localhost:8082"
        private const val DEMO_UIN = "1234567890"
    }
}
