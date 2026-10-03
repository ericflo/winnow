package com.ericflo.winnow.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ericflo.winnow.R
import com.ericflo.winnow.data.ProviderKind

/**
 * First run, in three steps: what Winnow does; becoming the SMS app (with the RCS trade-off
 * stated plainly); and how to classify. Everything here can be changed later in Settings.
 */
@Composable
fun OnboardingScreen(
    isDefault: () -> Boolean,
    onMakeDefault: () -> Unit,
    onChooseClassifier: (ProviderKind, apiKey: String) -> Unit,
    onFinish: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var defaultNow by rememberSaveable { mutableStateOf(isDefault()) }
    LifecycleResumeEffect(Unit) {
        defaultNow = isDefault()
        onPauseOrDispose { }
    }
    // A Surface, not just a background, so text gets onSurface instead of the default black.
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) { Column(
        Modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (step) {
                0 -> Welcome()
                1 -> BeDefault(defaultNow, onMakeDefault)
                else -> ChooseClassifier(onChooseClassifier)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            StepDots(step)
            Spacer(Modifier.weight(1f))
            if (step < 2) {
                TextButton(onClick = onFinish) { Text("Skip") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { step++ }) { Text("Next") }
            } else {
                Button(onClick = onFinish) { Text("Start") }
            }
        }
    } }
}

@Composable
private fun Welcome() {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 56.dp)) {
        Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_notification), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(48.dp))
        }
        Text("Texts worth your attention.", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Winnow is a full messaging app that reads every incoming text before it can buzz your phone.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Point("Scams, phishing, spam and political blasts go to Filtered, without a notification.")
        Point("Marketing arrives quietly. People you know always come through.")
        Point("Nothing is deleted, and one tap fixes a wrong call.")
    }
}

@Composable
private fun BeDefault(isDefault: Boolean, onMakeDefault: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 56.dp)) {
        Text("Make Winnow your SMS app", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Android only lets the default SMS app see texts before they notify you, so Winnow needs to be it.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("About RCS", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Android doesn't let apps other than Google Messages use RCS. In Winnow, chats send as SMS and MMS: " +
                        "group chats and photos still work, but typing indicators, read receipts and end-to-end " +
                        "encryption don't. You can switch back to Google Messages any time.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (isDefault) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Winnow is your SMS app", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Button(onClick = onMakeDefault) { Text("Set as default SMS app") }
        }
    }
}

@Composable
private fun ChooseClassifier(onChoose: (ProviderKind, String) -> Unit) {
    var kind by rememberSaveable { mutableStateOf(ProviderKind.ON_DEVICE) }
    var key by rememberSaveable { mutableStateOf("") }
    val options = listOf(ProviderKind.ON_DEVICE, ProviderKind.OPENROUTER_JEV, ProviderKind.TYPESAFE_JEV)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 56.dp)) {
        Text("How should Winnow decide?", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Messages from contacts, people you've texted and verification codes never leave this phone. " +
                "Anything sent to a classifier has numbers and links trimmed first.",
            style = MaterialTheme.typography.bodyLarge,
        )
        options.forEach { option ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = kind == option, role = Role.RadioButton, onClick = {
                        kind = option
                        onChoose(option, key)
                    }),
            ) {
                RadioButton(selected = kind == option, onClick = null, modifier = Modifier.padding(12.dp))
                Column(Modifier.padding(top = 10.dp)) {
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    Text(option.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (kind.needsApiKey) {
            OutlinedTextField(
                value = key,
                onValueChange = {
                    key = it
                    onChoose(kind, it.trim())
                },
                label = { Text("API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "More options, including your own server or any OpenAI-compatible model, are in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Point(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun StepDots(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(if (i == step) 10.dp else 8.dp)
                    .background(if (i == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
        }
    }
}
