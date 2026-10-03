package com.ericflo.winnow.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.classifier.message.SenderRule
import com.ericflo.winnow.data.ContactLookup
import kotlinx.coroutines.launch

/** Every "always allow" and "always filter" decision, each removable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SenderRulesScreen(container: AppContainer, onBack: () -> Unit) {
    val dao = container.verdictDao
    val rules by remember { dao.observeSenderRules() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sender rules") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            items(rules, key = { it.address }) { rule ->
                val allowed = rule.rule == SenderRule.ALWAYS_ALLOW.name
                ListItem(
                    headlineContent = { Text(container.contacts.displayName(rule.address) ?: ContactLookup.formatAddress(rule.address)) },
                    supportingContent = { Text(if (allowed) "Always reaches your inbox" else "Always filtered, no notification") },
                    trailingContent = {
                        IconButton(onClick = { scope.launch { dao.deleteSenderRule(rule.address) } }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove rule")
                        }
                    },
                )
            }
            if (rules.isEmpty()) {
                item {
                    Text(
                        "No sender rules yet. Tap \"Not spam\" or \"Filter sender\" in a conversation to add one.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
        }
    }
}
