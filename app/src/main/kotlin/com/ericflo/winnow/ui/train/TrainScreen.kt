package com.ericflo.winnow.ui.train

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classify.Training
import com.ericflo.winnow.ui.components.CategoryDot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the user said about one guess. */
sealed interface Decision {
    /** The guess was right. */
    data object Right : Decision

    /** It's [category] (another than the guess). */
    data class Is(val category: Category) : Decision

    /** Not sure: leave it unlabeled. */
    data object Skip : Decision
}

sealed interface TrainState {
    data object Loading : TrainState

    data class Reviewing(
        val round: Training.Round,
        val number: Int,
        val decisions: Map<Long, Decision> = emptyMap(),
        /** The conversation whose category choices are open. */
        val open: Long? = null,
    ) : TrainState

    data object Saving : TrainState

    data class Finished(val result: Training.RoundResult, val history: List<Training.RoundResult>, val backlog: Int, val labeled: Int) : TrainState

    /** Nothing left to label. */
    data class Done(val labeled: Int) : TrainState
}

class TrainViewModel(private val container: AppContainer) : ViewModel() {
    private val training get() = container.training
    private val _state = MutableStateFlow<TrainState>(TrainState.Loading)
    val state: StateFlow<TrainState> = _state.asStateFlow()

    init {
        nextRound()
    }

    fun nextRound() {
        _state.value = TrainState.Loading
        viewModelScope.launch {
            val round = training.nextRound()
            val number = training.history().size + 1
            _state.value = if (round.candidates.isEmpty()) TrainState.Done(round.labeled) else TrainState.Reviewing(round, number)
        }
    }

    private fun reviewing(change: (TrainState.Reviewing) -> TrainState.Reviewing) =
        _state.update { (it as? TrainState.Reviewing)?.let(change) ?: it }

    fun open(threadId: Long?) = reviewing { it.copy(open = if (it.open == threadId) null else threadId) }

    /** Marks [threadId]'s guess right, or takes that back. */
    fun toggleRight(threadId: Long) = reviewing {
        val next = if (it.decisions[threadId] == Decision.Right) it.decisions - threadId else it.decisions + (threadId to Decision.Right)
        it.copy(decisions = next, open = null)
    }

    fun decide(candidate: Training.Candidate, category: Category) = reviewing {
        val decision = if (category == candidate.guess) Decision.Right else Decision.Is(category)
        it.copy(decisions = it.decisions + (candidate.threadId to decision), open = null)
    }

    fun skip(threadId: Long) = reviewing { it.copy(decisions = it.decisions + (threadId to Decision.Skip), open = null) }

    /** "All right" for a group: every guess of [category] the user hasn't decided yet is right. */
    fun allRight(category: Category) = reviewing { r ->
        val undecided = r.round.candidates.filter { it.guess == category && it.threadId !in r.decisions }
        r.copy(decisions = r.decisions + undecided.associate { it.threadId to Decision.Right })
    }

    /** Labels what the user decided, retrains, and records how the round went. */
    fun finish() {
        val r = _state.value as? TrainState.Reviewing ?: return
        _state.value = TrainState.Saving
        // The app scope: leaving the screen mid-save mustn't lose the round.
        container.appScope.launch {
            val labels = r.round.candidates.mapNotNull { c ->
                when (val d = r.decisions[c.threadId]) {
                    Decision.Right -> c to c.guess
                    is Decision.Is -> c to d.category
                    else -> null
                }
            }
            labels.groupBy({ it.second }, { it.first }).forEach { (category, conversations) ->
                container.labeler.labelConversations(conversations.map { it.threadId to it.recipients }, category)
            }
            val result = Training.RoundResult(System.currentTimeMillis(), reviewed = labels.size, agreed = r.decisions.values.count { it == Decision.Right })
            if (labels.isNotEmpty()) training.record(result)
            _state.value = TrainState.Finished(
                result,
                training.history(),
                backlog = (r.round.backlog - labels.size).coerceAtLeast(0),
                labeled = r.round.labeled + labels.size,
            )
        }
    }
}

/**
 * Train Winnow: rounds of its guesses about the user's own conversations, each confirmed with a
 * tap or fixed with another; finishing a round labels them and retrains, so the next round's
 * guesses take the answers into account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainScreen(viewModel: TrainViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Train Winnow") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        bottomBar = {
            (state as? TrainState.Reviewing)?.let { r ->
                val checked = r.decisions.values.count { it != Decision.Skip }
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = viewModel::finish,
                        enabled = checked > 0,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    ) { Text(if (checked == 0) "Check a few to finish the round" else "Finish round · $checked of ${r.round.candidates.size} checked") }
                }
            }
        },
    ) { padding ->
        when (val s = state) {
            TrainState.Loading, TrainState.Saving -> Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(if (s == TrainState.Saving) "Learning from your labels…" else "Picking conversations…", style = MaterialTheme.typography.bodyMedium)
            }
            is TrainState.Reviewing -> Reviewing(s, viewModel, Modifier.padding(padding))
            is TrainState.Finished -> Finished(s, onNext = viewModel::nextRound, onDone = onBack, modifier = Modifier.padding(padding))
            is TrainState.Done -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Nothing left to label", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (s.labeled > 0) "You've labeled all ${s.labeled} conversations with people who aren't in your contacts. New ones show up here as they arrive."
                    else "There are no conversations with people outside your contacts to label. Winnow always lets your contacts through.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedButton(onClick = onBack) { Text("Done") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Reviewing(s: TrainState.Reviewing, viewModel: TrainViewModel, modifier: Modifier) {
    val groups = s.round.candidates.groupBy { it.guess }.toSortedMap(compareBy { it.ordinal })
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("intro") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Round ${s.number}", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Here's what Winnow thinks of ${s.round.candidates.size} of your conversations. Tap ✓ when it's right, or tap its guess to fix it. " +
                        "Anything you leave stays unlabeled. When you finish, Winnow learns from your answers before the next round.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val total = s.round.labeled + s.round.backlog
                if (total > 0) {
                    LinearProgressIndicator(progress = { s.round.labeled.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${s.round.labeled} of $total conversations with people outside your contacts labeled",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        groups.forEach { (category, candidates) ->
            item("h-${category.key}") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp)) {
                    CategoryDot(category)
                    Spacer(Modifier.width(12.dp))
                    Text("Winnow thinks: ${category.label} · ${candidates.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (candidates.any { it.threadId !in s.decisions }) TextButton(onClick = { viewModel.allRight(category) }) { Text("All right") }
                }
            }
            items(candidates, key = { it.threadId }) { c ->
                CandidateRow(
                    c,
                    decision = s.decisions[c.threadId],
                    open = s.open == c.threadId,
                    onToggleRight = { viewModel.toggleRight(c.threadId) },
                    onOpen = { viewModel.open(c.threadId) },
                    onPick = { viewModel.decide(c, it) },
                    onSkip = { viewModel.skip(c.threadId) },
                )
            }
        }
        item("end") { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CandidateRow(
    c: Training.Candidate,
    decision: Decision?,
    open: Boolean,
    onToggleRight: () -> Unit,
    onOpen: () -> Unit,
    onPick: (Category) -> Unit,
    onSkip: () -> Unit,
) {
    val shown = when (decision) {
        is Decision.Is -> decision.category
        else -> c.guess
    }
    val status = when (decision) {
        Decision.Right -> "Right"
        is Decision.Is -> "Changed to ${decision.category.label}"
        Decision.Skip -> "Skipped"
        null -> "Not checked"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).semantics { stateDescription = status },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(c.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryDot(shown)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (decision) {
                                null -> "${c.guess.label} · ${sureness(c.confidence)} · tap to change"
                                Decision.Right -> "${c.guess.label} · right"
                                is Decision.Is -> "${decision.category.label} · changed by you"
                                Decision.Skip -> "Skipped"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (decision != null && decision != Decision.Skip) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (decision == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                FilledIconToggleButton(checked = decision == Decision.Right, onCheckedChange = { onToggleRight() }) {
                    Icon(Icons.Filled.Check, contentDescription = "Winnow's right")
                }
            }
            if (open) {
                Text("It's actually…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Category.entries.forEach { category ->
                        FilterChip(
                            selected = shown == category && decision != null && decision != Decision.Skip,
                            onClick = { onPick(category) },
                            label = { Text(category.label) },
                            leadingIcon = { CategoryDot(category) },
                        )
                    }
                    FilterChip(selected = decision == Decision.Skip, onClick = onSkip, label = { Text("Not sure") })
                }
            }
        }
    }
}

@Composable
private fun Finished(s: TrainState.Finished, onNext: () -> Unit, onDone: () -> Unit, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("result") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                val r = s.result
                if (r.reviewed == 0) {
                    Text("Nothing labeled this round", style = MaterialTheme.typography.titleLarge)
                } else {
                    Text("Winnow was right on ${r.agreed} of ${r.reviewed}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        when (r.reviewed) {
                            1 -> "Winnow has learned from your answer. The next round's guesses take it into account."
                            2 -> "Winnow has learned from both your answers. The next round's guesses take them into account."
                            else -> "Winnow has learned from all ${r.reviewed} of your answers. The next round's guesses take them into account."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                val total = s.labeled + s.backlog
                if (total > 0) {
                    LinearProgressIndicator(progress = { s.labeled.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    Text("${s.labeled} of $total conversations labeled", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (s.history.size > 1) {
            item("history-h") { Text("Round by round", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(s.history.withIndex().reversed().take(10).toList(), key = { it.index }) { (i, round) ->
                Row {
                    Text("Round ${i + 1}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        "right on ${round.agreed} of ${round.reviewed}" + if (round.reviewed > 0) " (${round.agreed * 100 / round.reviewed}%)" else "",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        item("buttons") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp, bottom = 32.dp)) {
                if (s.backlog > 0) Button(onClick = onNext) { Text("Next round") }
                OutlinedButton(onClick = onDone) { Text("Done for now") }
            }
        }
    }
}

/**
 * How sure the model is of a guess, in words. Its probability is real, but "100% sure" claims
 * more than a model can know, so it isn't shown as a number.
 */
internal fun sureness(confidence: Double): String = when {
    confidence >= 0.9 -> "fairly sure"
    confidence >= 0.65 -> "leaning this way"
    else -> "unsure"
}
