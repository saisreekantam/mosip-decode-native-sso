package com.acme.sso.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** One step of a sign-in, shown to the user as the trust timeline. */
data class TraceStep(val ok: Boolean, val title: String, val detail: String)

/** Brand colours of the Acme apps: the Super App is blue, Pharmacy is green. */
object AcmeBrand {
    val SuperApp = Color(0xFF1E5BB8)
    val Pharmacy = Color(0xFF1B8A5A)
}

/** The Acme look: a fixed brand colour instead of the wallpaper-derived dynamic scheme. */
@Composable
fun AcmeTheme(brand: Color, content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = brand.copy(alpha = 1f).lighten(0.35f), secondary = brand.lighten(0.35f))
    } else {
        // Neutral surfaces: Material's defaults are tinted purple, which clashes with the brand colour.
        val neutral = Color(0xFFEEF1F5)
        lightColorScheme(primary = brand, secondary = brand, primaryContainer = brand.lighten(0.85f),
            onPrimaryContainer = brand.darken(0.5f), secondaryContainer = brand.lighten(0.88f),
            background = Color(0xFFFAFBFC), surface = Color(0xFFFAFBFC), surfaceVariant = neutral,
            surfaceContainer = neutral, surfaceContainerHigh = neutral, surfaceContainerHighest = neutral)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun Color.lighten(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)
private fun Color.darken(f: Float) = Color(red * (1 - f), green * (1 - f), blue * (1 - f), alpha)

/** The brand-coloured top bar, with a back arrow when [onBack] is given. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcmeTopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null) {
    TopAppBar(
        title = {
            Column {
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onPrimary,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

/** A check mark in a brand-coloured circle that springs in when it first appears. */
@Composable
fun SuccessTick(size: Dp = 56.dp, description: String = "Signed in") {
    val scale = remember { Animatable(0f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
    Box(
        Modifier.size(size).scale(scale.value).background(MaterialTheme.colorScheme.primary, CircleShape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(size * 0.6f))
    }
}

/** A small spinner shown next to a label while a network call runs. */
@Composable
fun BusyRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.semantics { contentDescription = label }) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The sign-in steps as a vertical timeline. Steps appear one after another; [showDetails] adds the
 * technical line under each step (key thumbprints, token fields) for developers and judges.
 */
@Composable
fun TrustTimeline(title: String, steps: List<TraceStep>, showDetails: Boolean) {
    var shown by remember(steps) { mutableIntStateOf(0) }
    LaunchedEffect(steps) {
        for (i in steps.indices) { delay(180); shown = i + 1 }
    }
    Column {
        Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() }.padding(bottom = 8.dp))
        steps.forEachIndexed { i, step ->
            AnimatedVisibility(visible = i < shown, enter = fadeIn() + slideInVertically { it / 2 }) {
                TimelineRow(step, last = i == steps.lastIndex, showDetails = showDetails)
            }
        }
    }
}

@Composable
private fun TimelineRow(step: TraceStep, last: Boolean, showDetails: Boolean) {
    val color = if (step.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        .semantics(mergeDescendants = true) { stateDescription = if (step.ok) "passed" else "failed" }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(32.dp).fillMaxHeight()) {
            Box(Modifier.size(24.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (step.ok) Icons.Filled.Check else Icons.Filled.Close, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            }
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(color.copy(alpha = 0.35f)))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.padding(bottom = if (last) 0.dp else 14.dp)) {
            Text(step.title, color = color, fontWeight = FontWeight.Medium)
            if (showDetails && step.detail.isNotEmpty()) Mono(step.detail)
        }
    }
}

/** A collapsible card holding the technical details that ordinary users do not need. */
@Composable
fun DeveloperDetails(expanded: Boolean, onToggle: (Boolean) -> Unit, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Developer details", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Switch(checked = expanded, onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = "Show developer details" })
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically()) {
                Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
            }
        }
    }
}

/** Monospace text for identifiers and token fields. */
@Composable
fun Mono(text: String) = Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)

/** A label and value on one line, for profile fields. */
@Composable
fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** Plain-language names of the identity details an app can ask for, by OAuth scope. */
object Details {
    private val byScope = mapOf(
        "profile" to listOf("Name", "Date of birth", "Gender", "Photo"),
        "email" to listOf("Email address"),
        "phone" to listOf("Phone number"),
    )
    private val byClaim = mapOf(
        "name" to "Name", "birthdate" to "Date of birth", "gender" to "Gender", "picture" to "Photo",
        "email" to "Email address", "phone_number" to "Phone number", "address" to "Address",
    )

    /** The details the scopes in [scope] release, in a stable order. */
    fun forScope(scope: String): List<String> = scope.split(' ').flatMap { byScope[it].orEmpty() }

    /** A human label for a userinfo claim, or null for technical claims. */
    fun label(claim: String): String? = byClaim[claim]
}
