package com.acme.pharmacy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.acme.sso.core.LoginLauncher
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
    private lateinit var session: ChildSession

    private val scope: String get() = if (askEmail.value) "openid profile email" else "openid profile"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = ChildSession.get(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize()) { Screen() } }
        }
        work { status.value = session.ensureRegistered() }
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
                .onFailure { trace.add(TraceStep(false, "Error", it.message ?: it.toString())) }
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
    private fun Screen() {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(applicationInfo.loadLabel(packageManager).toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Mono(session.clientId)

            val p = profile.value
            if (p != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Welcome, ${p.optString("name", "user")}", fontWeight = FontWeight.SemiBold)
                        p.keys().asSequence().filter { it !in setOf("iss", "aud", "sub", "exp", "iat", "nbf", "jti", "picture") }
                            .forEach { Mono("$it: ${p.optString(it)}") }
                        Mono(if (session.mode == "sso") "Signed in with your Acme account (Native SSO)" else "Signed in to Pharmacy")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { work { show(session.refreshProfile()) } }, enabled = !busy.value) { Text("Refresh profile") }
                    OutlinedButton(onClick = { session.signOut(); profile.value = null; trace.clear() }) { Text("Sign out here") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !askEmail.value, onClick = { askEmail.value = false }, label = { Text("profile") })
                    FilterChip(selected = askEmail.value, onClick = { askEmail.value = true }, label = { Text("profile + email") })
                }
                Button(onClick = { work { show(session.signInWithSso(scope)) } }, enabled = !busy.value, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue with your Acme account")
                }
            }

            consentNeeded.value?.let { missing ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Your consent is needed", fontWeight = FontWeight.SemiBold)
                        Text("You did not share \"${missing.ifEmpty { "these details" }}\" with the Acme Super App, so Pharmacy has to ask you.")
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

            if (trace.isNotEmpty()) {
                Text(if (trace.all { it.ok }) "Why this sign-in was trusted" else "Why this sign-in was refused", fontWeight = FontWeight.SemiBold)
                trace.forEach { step ->
                    Column {
                        Text((if (step.ok) "✓ " else "✗ ") + step.title,
                            color = if (step.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                        if (step.detail.isNotEmpty()) Mono(step.detail)
                    }
                }
            }
            if (status.value.isNotEmpty()) Mono(status.value)
        }
    }

    @Composable
    private fun Mono(text: String) = Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}
