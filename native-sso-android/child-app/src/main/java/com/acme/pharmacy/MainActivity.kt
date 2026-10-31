package com.acme.pharmacy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.acme.sso.core.Jose
import com.acme.sso.core.LoginLauncher
import com.acme.sso.ui.AcmeBrand
import com.acme.sso.ui.AcmeTheme
import com.acme.sso.ui.AcmeTopBar
import com.acme.sso.ui.BusyRow
import com.acme.sso.ui.Details
import com.acme.sso.ui.DeveloperDetails
import com.acme.sso.ui.Field
import com.acme.sso.ui.Mono
import com.acme.sso.ui.SuccessTick
import com.acme.sso.ui.TraceStep
import com.acme.sso.ui.TrustTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val trace = mutableStateListOf<TraceStep>()
    private val profile = mutableStateOf<JSONObject?>(null)
    private val consentNeeded = mutableStateOf<String?>(null)
    private val busy = mutableStateOf(false)
    private val status = mutableStateOf("")
    private val askEmail = mutableStateOf(false)
    private val developer = mutableStateOf(false)
    private lateinit var session: ChildSession

    private val scope: String get() = if (askEmail.value) "openid profile email" else "openid profile"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = ChildSession.get(this)
        setContent {
            AcmeTheme(AcmeBrand.Pharmacy) {
                Scaffold(topBar = { AcmeTopBar(applicationInfo.loadLabel(packageManager).toString()) }) { padding ->
                    Screen(Modifier.padding(padding))
                }
            }
        }
        work { status.value = session.ensureRegistered() }
        // The token survives the screen; reload the profile with it so the app reflects the
        // session's real state (for example, cut off after "Log out of all Acme apps").
        if (session.accessToken != null && intent?.data == null) work { show(session.refreshProfile()) }
        handleRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    private fun handleRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "com.acme.pharmacy") return
        work { show(session.completeConsentLogin(data)) }
    }

    private fun work(block: suspend () -> Unit) {
        busy.value = true
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onFailure { trace.add(TraceStep(false, "Something went wrong", it.message ?: it.toString())) }
            busy.value = false
        }
    }

    private fun show(outcome: SsoOutcome) {
        trace.clear()
        when (outcome) {
            is SsoOutcome.SignedIn -> { trace.addAll(outcome.trace); profile.value = outcome.claims; consentNeeded.value = null }
            is SsoOutcome.ConsentRequired -> { trace.addAll(outcome.trace); consentNeeded.value = outcome.missing }
            is SsoOutcome.Failed -> { trace.addAll(outcome.trace); if (session.accessToken == null) profile.value = null }
        }
    }

    @Composable
    private fun Screen(modifier: Modifier) {
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val p = profile.value
            if (p != null) SignedIn(p) else SignInChoice()

            consentNeeded.value?.let { missing -> ConsentCard(missing) }
            if (busy.value) BusyRow("Checking with eSignet…")

            if (trace.isNotEmpty()) {
                // A new list each time so the timeline replays its entrance for every attempt.
                TrustTimeline(
                    if (trace.all { it.ok }) "Why this sign-in was trusted" else "Why this sign-in was refused",
                    trace.toList(), showDetails = developer.value,
                )
            }
            DeveloperDetails(developer.value, { developer.value = it }) { TechnicalDetails(p) }
        }
    }

    @Composable
    private fun SignedIn(p: JSONObject) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SuccessTick(44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Welcome, ${p.optString("name", "user")}", fontWeight = FontWeight.SemiBold)
                        Text(if (session.mode == "sso") "You're signed in with your Acme account" else "You're signed in to Pharmacy",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("What Pharmacy can see", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                p.keys().asSequence().mapNotNull { k -> Details.label(k)?.takeIf { k != "picture" }?.let { it to p.optString(k) } }
                    .forEach { (label, value) -> Field(label, value) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { work { show(session.refreshProfile()) } }, enabled = !busy.value) { Text("Refresh profile") }
            OutlinedButton(onClick = { session.signOut(); profile.value = null; trace.clear() }) { Text("Sign out here") }
        }
    }

    @Composable
    private fun SignInChoice() {
        Text("Sign in with your Acme account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !askEmail.value, onClick = { askEmail.value = false }, label = { Text("profile") })
            FilterChip(selected = askEmail.value, onClick = { askEmail.value = true }, label = { Text("profile + email") })
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Pharmacy will see", fontWeight = FontWeight.Medium)
                Text(Details.forScope(scope).joinToString(", "), style = MaterialTheme.typography.bodyLarge)
                Text("No password or OTP: you are already signed in to Acme Super App on this phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
        Button(onClick = { work { show(session.signInWithSso(scope)) } }, enabled = !busy.value, modifier = Modifier.fillMaxWidth()) {
            Text("Continue with your Acme account")
        }
    }

    @Composable
    private fun ConsentCard(missing: String) {
        val details = Details.forScope(missing).ifEmpty { listOf("these details") }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Your consent is needed", fontWeight = FontWeight.SemiBold)
                }
                Text("Pharmacy asked for your ${details.joinToString(", ").lowercase()}. " +
                    "You did not share ${if (details.size == 1) "it" else "these"} when you signed in to Acme Super App, " +
                    "so Pharmacy has to ask you itself.")
                Text("Missing: ${details.joinToString(", ")}", fontWeight = FontWeight.Medium)
                Button(onClick = {
                    work {
                        val pending = session.startConsentLogin(scope)
                        withContext(Dispatchers.Main) {
                            LoginLauncher.open(this@MainActivity, pending.authorizeUrl, ChildSession.REDIRECT_URI)
                        }
                    }
                }, enabled = !busy.value) { Text("Review and consent") }
            }
        }
    }

    @Composable
    private fun TechnicalDetails(p: JSONObject?) {
        Text("Client", fontWeight = FontWeight.SemiBold)
        Mono(session.clientId)
        if (status.value.isNotEmpty()) Mono(status.value)
        session.accessToken?.let { token ->
            val at = Jose.payload(token)
            Text("Access token", fontWeight = FontWeight.SemiBold)
            Mono("mode ${if (session.mode == "sso") "Native SSO (bound to the Super App's key A)" else "own login (Pharmacy's key)"}")
            Mono("scope ${at.optString("scope")}")
            Mono("cnf.jkt ${at.optJSONObject("cnf")?.optString("jkt").orEmpty()}")
            at.optJSONObject("act")?.let { Mono("act.sub ${it.optString("sub")}") }
            Mono("expires ${java.util.Date(at.optLong("exp") * 1000)}")
        }
        p?.let {
            Text("Userinfo claims", fontWeight = FontWeight.SemiBold)
            it.keys().asSequence().forEach { k -> Mono("$k: ${it.optString(k).take(60)}") }
        }
    }
}
