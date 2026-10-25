package com.acme.superapp

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.acme.sso.core.BuildConfig
import com.acme.sso.core.DevProvisioning
import com.acme.sso.core.LoginLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private val log = mutableStateListOf<String>()
    private val busy = mutableStateOf(false)
    private val refresh = mutableStateOf(0)
    private val demoUin = mutableStateOf<String?>(null)
    private val profileName = mutableStateOf<String?>(null)
    private lateinit var session: SuperAppSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = SuperAppSession.get(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize()) { Screen() } }
        }
        run("Registering with eSignet") { session.ensureRegistered() }
        handleRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    private fun handleRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "com.acme.superapp") return
        run("Completing eSignet login") {
            val summary = session.completeLogin(data)
            profileName.value = session.profile()?.optString("name")
            summary
        }
    }

    private fun run(label: String, block: suspend () -> String) {
        busy.value = true
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { block() } }
            log.add(0, if (result.isSuccess) "✓ $label: ${result.getOrNull()}" else "✗ $label: ${result.exceptionOrNull()?.message}")
            busy.value = false
            refresh.value++
        }
    }

    @Composable
    private fun Screen() {
        refresh.value
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Acme Health", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Super App · Sign in with eSignet once, use every Acme app", style = MaterialTheme.typography.bodyMedium)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Device key (key A)", fontWeight = FontWeight.SemiBold)
                    Mono("jkt ${session.deviceKey.thumbprint}")
                    Mono(if (session.deviceKey.strongBox) "StrongBox secure element" else "Android Keystore (TEE)")
                    Text("Client", fontWeight = FontWeight.SemiBold)
                    Mono(session.clientId)
                    Mono("eSignet ${BuildConfig.ESIGNET_ISSUER}")
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (session.signedIn) {
                        Text("Signed in" + (profileName.value?.let { " as $it" } ?: ""), fontWeight = FontWeight.SemiBold)
                        Mono("device_secret held in encrypted storage")
                        Text("Apps sharing this sign-in: ${session.siblings.ifEmpty { listOf("none yet") }.joinToString()}")
                        OutlinedButton(onClick = { run("Logging out of all Acme apps") { session.logoutEverywhere() } },
                            enabled = !busy.value) { Text("Log out of all Acme apps") }
                    } else {
                        Text("Not signed in", fontWeight = FontWeight.SemiBold)
                        demoUin.value?.let { Mono("Demo UIN $it · OTP 111111") }
                        OutlinedButton(onClick = {
                            run("Creating demo identity") {
                                val uin = (1_000_000_000L + Random.nextLong(8_999_999_999L)).toString()
                                val res = DevProvisioning.createMockIdentity(MOCK_IDENTITY_URL, uin, "Asha Devi")
                                check(res.status == 200 && !res.body.contains("errorCode")) { res.body }
                                demoUin.value = uin
                                "UIN $uin (OTP 111111)"
                            }
                        }, enabled = !busy.value) { Text("Create demo user") }
                        Button(onClick = {
                            run("Starting eSignet login (PAR + DPoP)") {
                                val pending = session.startLogin()
                                withContext(Dispatchers.Main) {
                                    LoginLauncher.open(this@MainActivity, pending.authorizeUrl, SuperAppSession.REDIRECT_URI)
                                }
                                "opened the eSignet login UI"
                            }
                        }, enabled = !busy.value) { Text("Sign in with eSignet") }
                    }
                }
            }

            Text("Activity", fontWeight = FontWeight.SemiBold)
            log.forEach { Mono(it) }
        }
    }

    @Composable
    private fun Mono(text: String) = Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)

    companion object {
        private const val MOCK_IDENTITY_URL = "http://localhost:8082"
    }
}
