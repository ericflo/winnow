package com.ericflo.winnow.ui.runs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import com.ericflo.winnow.classifier.message.Subcategories
import com.ericflo.winnow.classify.BootstrapService
import com.ericflo.winnow.classify.BootstrapStatus
import com.ericflo.winnow.classify.Labeler
import com.ericflo.winnow.data.MessageTexts
import com.ericflo.winnow.data.db.RunAnswerEntity
import com.ericflo.winnow.data.db.RunEntity
import com.ericflo.winnow.ui.components.CategoryDot
import com.ericflo.winnow.ui.components.LabelSheet
import com.ericflo.winnow.ui.insight.BarRow
import com.ericflo.winnow.ui.insight.FilterRow
import com.ericflo.winnow.ui.insight.InsightCard
import com.ericflo.winnow.ui.insight.Note
import com.ericflo.winnow.ui.insight.RateBar
import com.ericflo.winnow.ui.insight.StatRow
import com.ericflo.winnow.ui.insight.count
import com.ericflo.winnow.ui.insight.duration
import com.ericflo.winnow.ui.insight.money
import com.ericflo.winnow.ui.insight.pct
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RunViewModel(private val container: AppContainer, private val runId: Long) : ViewModel() {
    val run: StateFlow<RunEntity?> = container.runDao.observe(runId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether this is the run going on right now. */
    val running: StateFlow<Boolean> = container.bootstrap.status
        .map { (it as? BootstrapStatus.Running)?.runId == runId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _rows = MutableStateFlow<List<AnswerRow>?>(null)
    /** Null while they're put together. */
    val rows: StateFlow<List<AnswerRow>?> = _rows.asStateFlow()

    /** Bumped when the user labels one, so the rows show their label. */
    private val labeled = MutableStateFlow(0)

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            // A running run adds a batch every few seconds: settled first, then rebuilt.
            combine(container.runDao.observeAnswers(runId).debounce(ANSWERS_SETTLE_MILLIS), labeled) { answers, _ -> answers }
                .collectLatest { answers -> _rows.value = build(answers) }
        }
        viewModelScope.launch {
            // Seen once it's over and on screen: the notification saying so has done its job.
            run.filterNotNull().collect { r ->
                if (r.finished && r.seenAt == null) {
                    container.runDao.markSeen(runId, System.currentTimeMillis())
                    BootstrapService.clearFinished(container.appContext)
                }
            }
        }
    }

    private suspend fun build(answers: List<RunAnswerEntity>): List<AnswerRow> = withContext(Dispatchers.IO) {
        val keys = answers.map { it.messageKey }
        val texts = MessageTexts(container.appContext).of(keys)
        val mine = keys.chunked(500).flatMap { container.verdictDao.forKeys(it) }
            .mapNotNull { v -> v.userCategory?.let(Category::fromKey)?.let { v.messageKey to it } }.toMap()
        val model = container.learner.classifier()
        withContext(Dispatchers.Default) {
            answers.mapNotNull { a ->
                val said = Category.fromKey(a.category) ?: return@mapNotNull null
                val text = texts[a.messageKey]?.body
                val now = text?.let { runCatching { model.classify(InboundMessage(a.address, it, senderInContacts = false, userHasMessagedSender = a.repliedTo)) }.getOrNull() }
                AnswerRow(
                    key = a.messageKey,
                    threadId = a.threadId,
                    address = a.address,
                    name = container.messages.displayName(a.address),
                    text = text,
                    said = said,
                    subcategory = a.subcategory,
                    confidence = a.confidence,
                    taught = a.taught,
                    before = a.modelCategory?.let(Category::fromKey),
                    beforeConfidence = a.modelConfidence,
                    now = now?.category,
                    nowConfidence = now?.confidence,
                    mine = mine[a.messageKey],
                    previous = a.previous?.let(Category::fromKey),
                )
            }
        }
    }

    private val policy = container.settings.settings.map { it.actionPolicy }
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.ericflo.winnow.classifier.message.ActionPolicy())

    /** Where a label files a conversation, by the user's own settings. */
    fun actionFor(category: Category) = policy.value.forCategory(category)

    /** Labels the conversation [row] is from as [category], as Train Winnow would; [onDone] gets what to say, and the undo. */
    fun label(row: AnswerRow, category: Category, onDone: (String, Labeler.Undo?) -> Unit) {
        container.appScope.launch {
            val result = container.labeler.labelConversations(listOf(row.threadId to listOf(row.address)), category)
            labeled.value++
            withContext(Dispatchers.Main) { onDone(Labeler.summary(category, result), result.undo) }
        }
    }

    fun undo(undo: Labeler.Undo) {
        container.appScope.launch {
            container.labeler.undo(undo)
            labeled.value++
        }
    }

    private companion object {
        const val ANSWERS_SETTLE_MILLIS = 600L
    }
}

/**
 * What a backlog run did, for good: what the service said of each text, what the on-device model
 * had made of it just before and makes of it now, and the user's own label where there is one.
 * Where the run's notification leads, and where Train Winnow's card does after it's gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunScreen(viewModel: RunViewModel, onBack: () -> Unit, onOpenThread: (Long, List<String>) -> Unit) {
    val run by viewModel.run.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(AnswerFilter.ALL) }
    var category by rememberSaveable { mutableStateOf<Category?>(null) }
    var labeling by remember { mutableStateOf<AnswerRow?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (run?.kind == RunEntity.KIND_REDO) "Asked again" else "Backlog run") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val r = run
        if (r == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val state = stateOf(r, running)
        val all = rows
        val summary = remember(all) { all?.let(RunSummary::of) }
        val shown = remember(all, filter, category) { all?.filter { filter.test(it) && (category == null || it.said == category) } }
        val provider = r.provider.substringBefore(" (")
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("head") { Header(r, state) }
            if (summary == null) {
                item("working") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp))
                        Text("Reading the answers…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                return@LazyColumn
            }
            if (summary.answered > 0) {
                item("said") {
                    InsightCard("What $provider said", subtitle = "Its answers, by category: ${count(summary.answered)} texts") {
                        val max = summary.byCategory.maxOf { it.second }
                        summary.byCategory.forEach { (c, n) -> BarRow(c.label, n, max, summary.answered, leading = { CategoryDot(c) }) }
                    }
                }
                item("model") { ModelAgreement(provider, summary) }
                item("you") { YouAgreement(provider, summary) }
                if (summary.askedBefore > 0) {
                    item("redo") {
                        InsightCard(
                            "Asked again",
                            subtitle = "Texts $provider had answered before" + if (r.examples > 0) ", asked now with ${count(r.examples)} of your labels as examples" else "",
                        ) {
                            RateBar("Answered the same", summary.askedBefore - summary.changed, summary.askedBefore, detail = "${count(summary.changed)} changed")
                            Note("A changed answer replaces the old one in what the on-device model learned. Your own labels are never touched.")
                        }
                    }
                }
            }
            item("filters") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text("Every answer", style = MaterialTheme.typography.titleMedium)
                    FilterRow(
                        options = AnswerFilter.entries.filter { f -> f == AnswerFilter.ALL || all.orEmpty().any(f::test) },
                        selected = filter,
                        label = { it.label },
                        onSelect = { filter = it },
                        counts = { f -> all.orEmpty().count(f::test) },
                    )
                    FilterRow(
                        options = listOf<Category?>(null) + Category.entries.filter { c -> all.orEmpty().any { it.said == c } },
                        selected = category,
                        label = { it?.label ?: "Any category" },
                        onSelect = { category = it },
                    )
                }
            }
            val list = shown.orEmpty()
            if (list.isEmpty()) {
                item("none") { Note(if (all.isNullOrEmpty()) "No answers yet." else "None of its answers fit these filters.", Modifier.padding(8.dp)) }
            }
            items(list, key = { it.key }) { row ->
                AnswerItem(row, provider, onOpen = { onOpenThread(row.threadId, listOf(row.address)) }, onLabel = { labeling = row })
            }
            item("about") {
                Note(
                    "Answers at least 70% sure teach the on-device model, each counting for ${pct(com.ericflo.winnow.classify.Learner.PROVIDER_WEIGHT)} of one of your labels; " +
                        "a label of yours on the same text replaces it. " +
                        (if (r.examples > 0) "Each question carried ${count(r.examples)} texts you'd labeled, as examples of how you sort. $provider doesn't learn from them: they shape its answers in this run only. " else "") +
                        "Settings can forget everything $provider taught.",
                    Modifier.padding(top = 8.dp),
                )
            }
        }
    }
    labeling?.let { row ->
        LabelSheet(
            title = "Label ${row.name}",
            current = row.mine,
            actionFor = viewModel::actionFor,
            onPick = { picked ->
                labeling = null
                viewModel.label(row, picked) { message, undo ->
                    scope.launch {
                        val result = snackbar.showSnackbar(message, actionLabel = if (undo != null) "Undo" else null, withDismissAction = undo == null)
                        if (result == SnackbarResult.ActionPerformed && undo != null) viewModel.undo(undo)
                    }
                }
            },
            onDismiss = { labeling = null },
        )
    }
}

@Composable
private fun Header(run: RunEntity, state: RunState) {
    val c = MaterialTheme.colorScheme
    val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    Surface(color = c.secondaryContainer, contentColor = c.onSecondaryContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(headline(run, state), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (state == RunState.RUNNING) {
                LinearProgressIndicator(progress = { if (run.planned == 0) 0f else run.done / run.planned.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
            val end = run.finishedAt ?: run.updatedAt
            Text(
                buildString {
                    append("Started ${format.format(Date(run.startedAt))}")
                    if (state != RunState.RUNNING) append(" · took ${duration(end - run.startedAt)}")
                    if (state == RunState.INTERRUPTED) append(". Winnow was closed before it finished; what it learned is kept, and the rest is offered again next run")
                    append(".")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            run.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.error) }
            StatRow(
                listOf(
                    count(run.labeled) to "taught the model",
                    count(run.unsure) to "too unsure",
                    count(run.kept) to "kept on phone",
                    count(run.failed) to "no answer",
                ),
            )
            Text(
                listOfNotNull(
                    "${run.provider}${run.model?.let { " · $it" } ?: ""}",
                    "cost ${money(run.costUsd)}",
                    "${count(run.planned)} texts from ${count(run.conversations)} conversations planned",
                    if (run.examples > 0) "${count(run.examples)} of your labels sent as examples" else "no examples sent",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ModelAgreement(provider: String, s: RunSummary) {
    InsightCard("$provider and your on-device model", subtitle = "How often the model on your phone already said what $provider said") {
        RateBar("Before it learned from these", s.beforeAgreed, s.beforeCompared, detail = "its own guess, just before each answer")
        RateBar("Now", s.nowAgreed, s.nowCompared, color = MaterialTheme.colorScheme.tertiary, detail = "today's model")
        if (s.disagreements.isNotEmpty()) {
            Text("Where they differed most", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            s.disagreements.take(4).forEach { (model, said, n) ->
                Text("Model said ${model.label}, $provider said ${said.label}: ${count(n)}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Note(
            "“Now” is after the model learned from these very answers, so it shows what it took from them, not how it does on texts it hasn't seen. " +
                "For that, see How accurate is Winnow?",
        )
    }
}

@Composable
private fun YouAgreement(provider: String, s: RunSummary) {
    InsightCard("$provider and you", subtitle = "Its answers on texts you've labeled yourself") {
        if (s.mineCompared == 0) {
            Note(
                "You haven't labeled any of these texts. Train Winnow brings the conversations where $provider and the model disagree first; " +
                    "every one you label there shows here how often $provider agrees with you.",
            )
        } else {
            RateBar("Agreed with your label", s.mineAgreed, s.mineCompared)
            Note("Your label wins wherever the two differ: it replaces $provider's answer in what the model learned.")
        }
    }
}

@Composable
private fun AnswerItem(row: AnswerRow, provider: String, onOpen: () -> Unit, onLabel: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(color = c.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(onClickLabel = "Open the conversation", onClick = onOpen).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(row.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.text ?: "This text is no longer on your phone.", style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, color = if (row.text == null) c.onSurfaceVariant else c.onSurface)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryDot(row.said)
                Spacer(Modifier.width(8.dp))
                val kind = row.subcategory?.let(Subcategories::of)?.takeIf { it.parent == row.said }?.key?.replace('_', ' ')
                Text(
                    "$provider: ${row.said.label}${kind?.let { " · $it" } ?: ""} · ${pct(row.confidence)}${if (!row.taught) " · too unsure to teach" else ""}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            val model = listOfNotNull(
                row.before?.let { "before: ${it.label} ${row.beforeConfidence?.let(::pct).orEmpty()}" },
                row.now?.let { "now: ${it.label} ${row.nowConfidence?.let(::pct).orEmpty()}" },
            )
            if (model.isNotEmpty()) {
                Text(
                    "On-device model " + model.joinToString(" → "),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (row.now != null && row.now != row.said) c.tertiary else c.onSurfaceVariant,
                    modifier = Modifier.padding(start = 32.dp, top = 2.dp),
                )
            }
            row.previous?.takeIf { it != row.said }?.let {
                Text("$provider said ${it.label} last time", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 32.dp, top = 2.dp))
            }
            row.mine?.let {
                Text(
                    "You labeled it ${it.label}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (it != row.said) c.error else c.primary,
                    modifier = Modifier.padding(start = 32.dp, top = 2.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onLabel) { Text(if (row.mine == null) "Label it" else "Change label") }
            }
        }
    }
}

/** Every run, newest first: where Train Winnow's "past runs" and the Activity screen lead. */
class RunsViewModel(container: AppContainer) : ViewModel() {
    val runs: StateFlow<List<RunEntity>?> = container.runDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val runningId: StateFlow<Long?> = container.bootstrap.status.map { (it as? BootstrapStatus.Running)?.runId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunsScreen(viewModel: RunsViewModel, onBack: () -> Unit, onOpenRun: (Long) -> Unit) {
    val runs by viewModel.runs.collectAsStateWithLifecycle()
    val runningId by viewModel.runningId.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Runs") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        val list = runs
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("about") {
                Note("Each time you let a classifier service label your backlog, what it said of every text is kept here, with what it taught Winnow's model.")
            }
            if (list != null && list.isEmpty()) {
                item("none") { Note("No runs yet. Train Winnow offers one once a classifier service is set up in Settings.") }
            }
            items(list.orEmpty(), key = { it.id }) { run ->
                val state = stateOf(run, run.id == runningId)
                val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                InsightCard(headline(run, state), subtitle = format.format(Date(run.startedAt)) + " · " + run.provider, onClick = { onOpenRun(run.id) }) {
                    Text(
                        listOfNotNull(
                            "${count(run.labeled)} taught",
                            run.unsure.takeIf { it > 0 }?.let { "${count(it)} too unsure" },
                            run.kept.takeIf { it > 0 }?.let { "${count(it)} kept on phone" },
                            run.failed.takeIf { it > 0 }?.let { "${count(it)} no answer" },
                            "cost ${money(run.costUsd)}",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (run.seenAt == null && state != RunState.RUNNING) Text("New", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
