package com.ericflo.winnow.ui.train

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** One conversation of a finished round: Winnow's guess, and the user's answer (null if skipped). */
    data class Outcome(val name: String, val text: String, val guess: Category, val answer: Category?)

    data class Finished(
        val result: Training.RoundResult,
        /** Every conversation of the round, as shown. */
        val outcomes: List<Outcome> = emptyList(),
        val history: List<Training.RoundResult>,
        val backlog: Int,
        val labeled: Int,
        /** Sender rules the round's labels disagreed with, and so removed. */
        val rulesRemoved: Int = 0,
    ) : TrainState

    /** Nothing left to label. */
    data class Done(val labeled: Int) : TrainState
}

/** What the screen can say about letting the classifier service label the backlog (see Bootstrap). */
data class BootstrapOffer(
    val provider: String,
    /** Why it can't run now, in words; null when it can. */
    val unavailable: String?,
    val plan: com.ericflo.winnow.classify.Bootstrap.Plan,
    /** Labels it has given so far. */
    val taught: Int,
    /** What sending the plan would cost at a price we know (Jev via OpenRouter's), else null. */
    val estimateUsd: Double?,
    /** Asking again about texts answered before, with the user's latest labels. */
    val redo: Boolean = false,
)

class TrainViewModel(private val container: AppContainer) : ViewModel() {
    private val training get() = container.training
    private val _state = MutableStateFlow<TrainState>(TrainState.Loading)
    val state: StateFlow<TrainState> = _state.asStateFlow()

    val bootstrap: StateFlow<com.ericflo.winnow.classify.BootstrapStatus> = container.bootstrap.status
    private val _offer = MutableStateFlow<BootstrapOffer?>(null)
    val offer: StateFlow<BootstrapOffer?> = _offer.asStateFlow()

    init {
        nextRound()
        loadOffer()
    }

    private fun loadOffer(redo: Boolean = false) {
        viewModelScope.launch {
            val current = container.settings.current()
            val plan = runCatching { container.bootstrap.plan(redo) }.getOrNull() ?: return@launch
            val taught = container.bootstrap.taught.first()
            _offer.value = BootstrapOffer(
                provider = current.provider.label,
                unavailable = container.bootstrap.unavailable(current),
                plan = plan,
                taught = taught,
                estimateUsd = if (current.provider == com.ericflo.winnow.data.ProviderKind.OPENROUTER_JEV) plan.texts * JEV_OPENROUTER_USD_PER_TEXT else null,
                redo = redo,
            )
        }
    }

    /** In a foreground service, so the run goes on with the screen off or Winnow closed. */
    fun startBootstrap() = com.ericflo.winnow.classify.BootstrapService.start(container.appContext)

    /** Plans a redo (see Bootstrap.plan); the card then offers it for confirmation. */
    fun planRedo() = loadOffer(redo = true)

    /** Back to the ordinary offer, when a redo isn't wanted after all. */
    fun cancelRedo() = loadOffer(redo = false)

    fun stopBootstrap() = container.bootstrap.stop()

    /** After a run: the next round is guessed by the model it taught. */
    fun dismissBootstrap() {
        container.bootstrap.dismiss()
        loadOffer()
        nextRound()
    }

    private companion object {
        const val REGUESS_SETTLE_MILLIS = 350L

        /** Jev via OpenRouter, measured 2026-10-05: 773 input tokens at $0.000000042 each, output free. */
        const val JEV_OPENROUTER_USD_PER_TEXT = 0.0000325
    }

    fun nextRound() {
        _state.value = TrainState.Loading
        viewModelScope.launch {
            val round = training.nextRound()
            val number = training.history().size + 1
            _state.value = if (round.candidates.isEmpty()) TrainState.Done(round.labeled) else TrainState.Reviewing(round, number)
        }
    }

    private fun reviewing(change: (TrainState.Reviewing) -> TrainState.Reviewing) {
        val before = (_state.value as? TrainState.Reviewing)?.decisions
        _state.update { (it as? TrainState.Reviewing)?.let(change) ?: it }
        if ((_state.value as? TrainState.Reviewing)?.decisions != before) reguess()
    }

    private var reguessing: Job? = null

    /**
     * Re-guesses the conversations not answered yet, from a model taught the answers given so
     * far (nothing is saved until Finish): label one pharmacy text and its lookalikes in the
     * round move with it. The latest answer wins; an earlier re-guess still running is dropped.
     */
    private fun reguess() {
        reguessing?.cancel()
        reguessing = viewModelScope.launch {
            // Answers given in quick succession ("All right", a run of ✓s) make one refit, not one each.
            kotlinx.coroutines.delay(REGUESS_SETTLE_MILLIS)
            val r = _state.value as? TrainState.Reviewing ?: return@launch
            val answers = r.round.candidates.mapNotNull { c ->
                when (val d = r.decisions[c.threadId]) {
                    Decision.Right -> c.message() to c.guess
                    is Decision.Is -> c.message() to d.category
                    else -> null
                }
            }
            val model = container.learner.preview(answers)
            val open = r.round.candidates.filter { it.threadId !in r.decisions }
            val fresh = withContext(Dispatchers.Default) { open.associate { c -> c.threadId to model.classify(c.message()) } }
            reviewing { now ->
                now.copy(
                    round = now.round.copy(
                        candidates = now.round.candidates.map { c ->
                            val p = fresh[c.threadId]
                            if (p == null || c.threadId in now.decisions) c else c.copy(guess = p.category, confidence = p.confidence)
                        },
                    ),
                )
            }
        }
    }

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
            val rulesRemoved = labels.groupBy({ it.second }, { it.first }).entries.sumOf { (category, conversations) ->
                container.labeler.labelConversations(conversations.map { it.threadId to it.recipients }, category).rulesRemoved.size
            }
            val result = Training.RoundResult(
                System.currentTimeMillis(),
                reviewed = labels.size,
                agreed = r.decisions.values.count { it == Decision.Right },
                skipped = r.round.candidates.size - labels.size,
            )
            val outcomes = r.round.candidates.map { c ->
                val answer = when (val d = r.decisions[c.threadId]) {
                    Decision.Right -> c.guess
                    is Decision.Is -> d.category
                    else -> null
                }
                TrainState.Outcome(c.name, c.text, c.guess, answer)
            }
            if (labels.isNotEmpty()) training.record(result)
            _state.value = TrainState.Finished(
                result,
                outcomes,
                training.history(),
                backlog = (r.round.backlog - labels.size).coerceAtLeast(0),
                labeled = r.round.labeled + labels.size,
                rulesRemoved = rulesRemoved,
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
fun TrainScreen(viewModel: TrainViewModel, onBack: () -> Unit, onOpenThread: (Long, List<String>) -> Unit = { _, _ -> }) {
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
            is TrainState.Reviewing -> Reviewing(s, viewModel, onOpenThread, Modifier.padding(padding))
            is TrainState.Finished -> Finished(s, onNext = viewModel::nextRound, onDone = onBack, modifier = Modifier.padding(padding))
            is TrainState.Done -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BootstrapSection(viewModel)
                Text("Nothing left to label", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (s.labeled > 0) "You've sorted all ${s.labeled} conversations with people who aren't in your contacts. New ones show up here as they arrive."
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
private fun Reviewing(s: TrainState.Reviewing, viewModel: TrainViewModel, onOpenThread: (Long, List<String>) -> Unit, modifier: Modifier) {
    val offer by viewModel.offer.collectAsStateWithLifecycle()
    val groups = s.round.candidates.groupBy { it.guess }.toSortedMap(compareBy { it.ordinal })
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("bootstrap") { Box(Modifier.padding(horizontal = 16.dp)) { BootstrapSection(viewModel) } }
        item("intro") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Round ${s.number}", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Here's what Winnow thinks of ${s.round.candidates.size} of your conversations. Tap ✓ when it's right, or tap its guess to fix it. " +
                        "Its other guesses update as you answer, so texts like one you fixed follow it. Anything you leave stays unlabeled.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val total = s.round.labeled + s.round.backlog
                if (total > 0) {
                    LinearProgressIndicator(progress = { s.round.labeled.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${s.round.labeled} of $total conversations with people outside your contacts sorted by you",
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
                // A row whose guess changes moves to its new group, visibly.
                Box(Modifier.animateItem()) {
                CandidateRow(
                    c,
                    onOpenThread = { onOpenThread(c.threadId, c.recipients) },
                    providerName = offer?.provider?.let { if ("Jev" in it) "Jev" else it } ?: "Jev",
                    decision = s.decisions[c.threadId],
                    open = s.open == c.threadId,
                    onToggleRight = { viewModel.toggleRight(c.threadId) },
                    onOpen = { viewModel.open(c.threadId) },
                    onPick = { viewModel.decide(c, it) },
                    onSkip = { viewModel.skip(c.threadId) },
                )
                }
            }
        }
        item("end") { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CandidateRow(
    c: Training.Candidate,
    /** Opens the whole conversation, the user's own texts and all. */
    onOpenThread: () -> Unit,
    /** The classifier service, as the user knows it ("Jev (TypeSafe)"). */
    providerName: String,
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
                Column(Modifier.weight(1f)) {
                    // The text, a few lines of it until tapped: then all of it, and the earlier texts
                    // from them that a label here covers too, so nothing has to be guessed at.
                    var expanded by rememberSaveable(c.threadId) { mutableStateOf(false) }
                    var overflows by remember(c.threadId) { mutableStateOf(false) }
                    val expandable = expanded || overflows || c.earlier.isNotEmpty()
                    // Nothing more to show here: a tap opens the conversation itself, for context.
                    Column(
                        Modifier.clickable(
                            onClickLabel = when {
                                expanded -> "Show less"
                                expandable -> "Read the whole message"
                                else -> "See the conversation"
                            },
                        ) { if (expandable) expanded = !expanded else onOpenThread() },
                    ) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            c.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                        )
                        if (expanded && c.earlier.isNotEmpty()) {
                            Text(
                                "Earlier from them, labeled along with it",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                            c.earlier.forEach { text ->
                                Text(
                                    text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                        if (expanded) {
                            TextButton(onClick = onOpenThread, contentPadding = PaddingValues(0.dp)) { Text("Open the whole conversation") }
                        }
                        run {
                            Text(
                                when {
                                    expanded -> "Show less"
                                    !expandable -> "See the conversation"
                                    c.earlier.isEmpty() -> "Read more"
                                    c.earlier.size == 1 -> "Read more · 1 earlier text"
                                    else -> "Read more · ${c.earlier.size} earlier texts"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onOpen)) {
                        CategoryDot(shown)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (decision) {
                                null -> if (c.guess != c.firstGuess) "${c.guess.label} · from your answers · tap to change"
                                    else "${c.guess.label} · ${sureness(c.confidence)} · tap to change"
                                Decision.Right -> "${c.guess.label} · right"
                                is Decision.Is -> "${decision.category.label} · changed by you"
                                Decision.Skip -> "Skipped"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (decision != null && decision != Decision.Skip) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (decision == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    // The classifier service's own answer, so its part in the guess is plain to see.
                    c.providerSays?.let { says ->
                        Text(
                            "$providerName said ${says.label}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (says == c.guess) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(start = 32.dp, top = 2.dp),
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
                    Text(
                        if (r.skipped == 0) "Winnow was right on ${r.agreed} of ${r.reviewed}" else "Winnow was right on ${r.agreed} of the ${r.reviewed} you answered",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (r.skipped > 0) {
                        Text(
                            if (r.skipped == 1) "1 you left as Not sure or unchecked stays unlabeled." else "${r.skipped} you left as Not sure or unchecked stay unlabeled.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        // Rounds are the conversations it's least sure of, so their scores don't climb
                        // the way its accuracy on everything else does; it mustn't sound like they will.
                        "Winnow learned from your answers: texts like these now get your label. Each round brings the " +
                            "conversations it's least sure of, so a round's score isn't a measure of how much it has learned.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (s.rulesRemoved > 0) {
                        Text(
                            if (s.rulesRemoved == 1) "Your answers disagreed with a sender rule, so it's gone: that sender's texts are judged afresh."
                            else "Your answers disagreed with ${s.rulesRemoved} sender rules, so they're gone: those senders' texts are judged afresh.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val total = s.labeled + s.backlog
                if (total > 0) {
                    LinearProgressIndicator(progress = { s.labeled.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    Text("${s.labeled} of $total conversations sorted by you", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val wrong = s.outcomes.filter { it.answer != null && it.answer != it.guess }
        if (wrong.isNotEmpty()) {
            item("wrong-h") { Text("Where Winnow was wrong", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(wrong, key = { "wrong-" + it.name + it.text.hashCode() }) { o ->
                Column {
                    Text(o.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(o.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Winnow said ${o.guess.label} · you said ${o.answer!!.label}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (s.history.size > 1) {
            item("history-h") { Text("Round by round", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(s.history.withIndex().reversed().take(10).toList(), key = { it.index }) { (i, round) ->
                Row {
                    Text("Round ${i + 1}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        // A percentage of a handful claims more than it shows.
                        "right on ${round.agreed} of ${round.reviewed}" + (if (round.reviewed >= 10) " (${round.agreed * 100 / round.reviewed}%)" else "") +
                            if (round.skipped > 0) " · ${round.skipped} skipped" else "",
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

/** How many lines of a message a review row shows until it's tapped open. */
private const val COLLAPSED_LINES = 3

/**
 * Letting the classifier service label the backlog first (see Bootstrap): what it would send,
 * a confirmation saying exactly that, then progress, then how it went.
 */
@Composable
private fun BootstrapSection(viewModel: TrainViewModel) {
    val offer by viewModel.offer.collectAsStateWithLifecycle()
    val status by viewModel.bootstrap.collectAsStateWithLifecycle()
    val o = offer ?: return
    var confirming by rememberSaveable { mutableStateOf(false) }
    val money = { usd: Double -> if (usd < 0.01) "under a cent" else String.format(java.util.Locale.US, "$%.2f", usd) }
    when (val st = status) {
        is com.ericflo.winnow.classify.BootstrapStatus.Running -> BootstrapCard("${o.provider} is labeling your backlog") {
            LinearProgressIndicator(progress = { if (st.total == 0) 0f else st.done.toFloat() / st.total }, modifier = Modifier.fillMaxWidth())
            Text(
                "${st.done} of ${st.total} texts · ${st.tally.labeled} labeled · ${money(st.tally.costUsd)} so far" +
                    (if (st.tally.unsure > 0) " · ${st.tally.unsure} too unsure to teach" else "") +
                    (if (st.tally.kept > 0) " · ${st.tally.kept} kept on your phone" else "") +
                    (if (st.tally.failed > 0) " · ${st.tally.failed} to try again" else ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            st.pausedFor?.let { millis ->
                Text(
                    com.ericflo.winnow.classify.Bootstrap.pauseText(o.provider, st.trouble, millis),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = viewModel::stopBootstrap, contentPadding = PaddingValues(0.dp)) { Text("Stop") }
        }
        is com.ericflo.winnow.classify.BootstrapStatus.Finished -> BootstrapCard(if (st.stopped) "Stopped" else "${o.provider} labeled your backlog") {
            Text(
                (if (st.tally.labeled == 0) "No texts labeled this time."
                else "${plural(st.tally.labeled, "text")} labeled, for ${money(st.tally.costUsd)}. Winnow's model has learned from them; your own labels count for more and always win.") +
                    (if (st.tally.unsure > 0) " ${st.tally.unsure} more got an answer too unsure to teach." else "") +
                    (if (st.tally.kept > 0) " ${st.tally.kept} stayed on your phone, as your privacy settings say." else "") +
                    (if (st.tally.failed > 0) " ${st.tally.failed} got no answer and will be tried next time." else "") +
                    (st.error?.let { " $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = viewModel::dismissBootstrap) { Text("Continue to a round") }
        }
        com.ericflo.winnow.classify.BootstrapStatus.Idle -> when {
            o.plan.texts == 0 && o.taught == 0 -> Unit
            o.redo -> BootstrapCard("Ask ${o.provider} again with your latest labels") {
                Text(
                    "It would ask again about ${plural(o.plan.texts, "text")} from ${plural(o.plan.conversations, "conversation")}" +
                        (if (o.plan.examples > 0) ", this time with ${plural(o.plan.examples, "text")} you labeled as examples of how you sort" else "") +
                        ". Its new answers replace its old ones; yours are never touched.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (o.unavailable != null) {
                    Text(o.unavailable, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { confirming = true }, enabled = o.plan.texts > 0) { Text("Review and start") }
                        TextButton(onClick = viewModel::cancelRedo) { Text("Not now") }
                    }
                }
            }
            o.plan.texts == 0 -> Column {
                Text(
                    "${o.provider} has labeled your backlog (${o.taught} texts). When new conversations come in, they're offered here to label too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = viewModel::planRedo, contentPadding = PaddingValues(0.dp)) { Text("Ask ${o.provider} again with your latest labels") }
            }
            else -> BootstrapCard(if (o.taught > 0) "Finish labeling your backlog with ${o.provider}" else "Let ${o.provider} label your backlog first") {
                Text(
                    "It would label ${plural(o.plan.texts, "text")} from ${plural(o.plan.conversations, "conversation")} with people who aren't in your contacts, " +
                        "so Winnow learns your texts before you've labeled many, and these rounds bring only what it still can't settle.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (o.unavailable != null) {
                    Text(o.unavailable, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                } else {
                    Button(onClick = { confirming = true }) { Text("Review and start") }
                }
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(if (o.redo) "Ask ${o.provider} again about ${plural(o.plan.texts, "text")}?" else "Send ${plural(o.plan.texts, "text")} to ${o.provider}?") },
            text = {
                Text(consentText(o, money))
            },
            confirmButton = { TextButton(onClick = { confirming = false; viewModel.startBootstrap() }) { Text("Start") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Not now") } },
        )
    }
}

@Composable
private fun BootstrapCard(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/**
 * What the bootstrap will do, as the confirmation says it: worked out from the user's own
 * privacy settings, so every claim in it is true of this run.
 */
private fun consentText(o: BootstrapOffer, money: (Double) -> String): String {
    val p = o.plan.privacy
    val r = p.redaction
    val masked = listOfNotNull("long numbers".takeIf { r.maskDigitRuns }, "email addresses".takeIf { r.maskEmails }, "the paths of links".takeIf { r.stripUrlPaths })
    val stay = listOfNotNull(
        "contacts",
        "people you've written to".takeIf { !p.classifyKnownConversations },
        "verification codes".takeIf { !p.classifyVerificationCodes },
        "senders you've set a rule for",
    )
    fun list(items: List<String>) = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }
    return buildString {
        append("Up to the newest ${com.ericflo.winnow.classify.Bootstrap.PER_CONVERSATION} texts of each of ${plural(o.plan.conversations, "conversation")} with people who aren't in your contacts go to ${o.provider}, a few at a time, with zero data retention: ")
        append(if (o.provider.contains("OpenRouter")) "OpenRouter is told to use only endpoints that keep nothing. " else "you've confirmed ${o.provider} keeps nothing (Winnow can't check that itself). ")
        append(if (masked.isEmpty()) "Your settings send them unmasked. " else "${list(masked).replaceFirstChar { it.uppercase() }} are masked first, as your settings say. ")
        append(if (p.shareSenderAddress) "Your settings send the sender's number with each. " else "The sender's number isn't sent. ")
        append("Texts from ${list(stay)} stay on your phone.")
        if (p.classifyKnownConversations) append(" Your settings do send texts from people you've written to.")
        if (p.classifyVerificationCodes) append(" Your settings do send verification codes.")
        if (o.plan.examples > 0) {
            append(
                " With each one go ${plural(o.plan.examples, "text")} you labeled yourself (up to ${com.ericflo.winnow.classify.Bootstrap.EXAMPLES_PER_CATEGORY} per category, " +
                    "chosen and redacted by the same rules), so it sorts the way you do, not by its own idea of the categories.",
            )
        }
        append("\n\nIts answers teach Winnow's model (counting for less than your labels, which always win) and file texts Winnow never sorted. ")
        append(o.estimateUsd?.let { "At OpenRouter's price for Jev that's about ${money(it)} in all. " } ?: "${o.provider} bills each one as usual; the cost so far shows as it goes. ")
        append("You can stop at any time and pick up later; texts it has answered aren't sent again.")
    }
}

private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
