package com.ericflo.winnow.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.data.VerdictRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class Window(val label: String, val days: Long?) { WEEK("7 days", 7), MONTH("30 days", 30), ALL("All time", null) }

data class ActivityUiState(
    val window: Window = Window.MONTH,
    val checked: Int = 0,
    val filtered: Int = 0,
    val silenced: Int = 0,
    val delivered: Int = 0,
    /** Every category, most common first, including zeros. */
    val byCategory: List<Pair<Category, Int>> = emptyList(),
    val onPhone: Int = 0,
    val byProvider: Int = 0,
    val costUsd: Double = 0.0,
    val topFiltered: List<Pair<String, Int>> = emptyList(),
)

class ActivityViewModel(private val container: AppContainer) : ViewModel() {
    private val window = MutableStateFlow(Window.MONTH)

    val state: StateFlow<ActivityUiState> = combine(container.messages.verdictRecords(), window) { records, window ->
        val since = window.days?.let { System.currentTimeMillis() - it * 24 * 3_600_000 } ?: Long.MIN_VALUE
        summarize(records.filter { it.decidedAt >= since }, window)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState())

    fun select(value: Window) {
        window.value = value
    }

    private fun summarize(records: List<VerdictRecord>, window: Window): ActivityUiState {
        val counts = records.groupingBy { it.category }.eachCount()
        return ActivityUiState(
            window = window,
            checked = records.size,
            filtered = records.count { it.action == Action.FILTER },
            silenced = records.count { it.action == Action.SILENCE },
            delivered = records.count { it.action == Action.ALLOW },
            byCategory = Category.entries.map { it to (counts[it] ?: 0) }.sortedByDescending { it.second },
            onPhone = records.count { !it.byProvider },
            byProvider = records.count { it.byProvider },
            costUsd = records.sumOf { it.costUsd },
            topFiltered = records.filter { it.action == Action.FILTER }.groupingBy { it.sender }.eachCount()
                .entries.sortedByDescending { it.value }.take(5)
                .map { (sender, n) -> container.messages.displayName(sender) to n },
        )
    }
}

/** What Winnow has been doing: how many texts it checked, and what it did with them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(viewModel: ActivityViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        ) {
            item("window") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Window.entries.forEachIndexed { i, w ->
                        SegmentedButton(
                            selected = w == state.window,
                            onClick = { viewModel.select(w) },
                            shape = SegmentedButtonDefaults.itemShape(i, Window.entries.size),
                        ) { Text(w.label) }
                    }
                }
            }
            item("tiles") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Filtered", state.filtered, Modifier.weight(1f))
                    StatTile("Silenced", state.silenced, Modifier.weight(1f))
                    StatTile("Delivered", state.delivered, Modifier.weight(1f))
                }
            }
            item("categories") {
                Card("What arrived") {
                    if (state.checked == 0) {
                        Text("Nothing checked in this period yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val max = state.byCategory.maxOf { it.second }.coerceAtLeast(1)
                        state.byCategory.forEach { (category, count) -> CategoryBar(category.label, count, max) }
                    }
                }
            }
            item("deciders") {
                Card("Who decided") {
                    Line("On this phone", "${state.onPhone}", "Contacts, people you've texted, codes, sender rules, Winnow's model")
                    Line("Classifier service", "${state.byProvider}", if (state.costUsd > 0) "About $${"%.4f".format(state.costUsd)} in total" else null)
                }
            }
            if (state.topFiltered.isNotEmpty()) {
                item("senders") {
                    Card("Most filtered senders") {
                        state.topFiltered.forEach { (name, n) -> Line(name, "$n", null) }
                    }
                }
            }
            item("footer") {
                Text(
                    "Counted on this phone from Winnow's own records. ${state.checked} messages checked in this period.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: Int, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp), modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text("$value", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** One labeled bar. A single hue for every row: the label, not the color, names the category. */
@Composable
private fun CategoryBar(label: String, count: Int, max: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$label: $count" },
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(110.dp))
        Box(Modifier.weight(1f).height(8.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(4.dp))) {
            if (count > 0) {
                Box(Modifier.fillMaxWidth(count / max.toFloat()).height(8.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
            }
        }
        Text("$count", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(40.dp).padding(start = 12.dp))
    }
}

@Composable
private fun Line(label: String, value: String, detail: String?) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
