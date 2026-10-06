package com.ericflo.winnow.ui.model

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ericflo.winnow.AppContainer
import com.ericflo.winnow.classifier.local.ClassifierMetrics
import com.ericflo.winnow.classifier.local.Featurizer
import com.ericflo.winnow.classifier.local.LocalModel
import com.ericflo.winnow.classifier.local.OnDeviceClassifier
import com.ericflo.winnow.classifier.local.Personalizer
import com.ericflo.winnow.classifier.message.ActionPolicy
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import com.ericflo.winnow.classify.Agreement3
import com.ericflo.winnow.classify.DeciderCounts
import com.ericflo.winnow.classify.Learner
import com.ericflo.winnow.classify.ModelInsight
import com.ericflo.winnow.classify.ModelInspector
import com.ericflo.winnow.classify.WeekAgreement
import com.ericflo.winnow.data.MessageTexts
import com.ericflo.winnow.data.ProviderKind
import com.ericflo.winnow.data.db.ModelFitEntity
import com.ericflo.winnow.ui.components.CategoryDot
import com.ericflo.winnow.ui.insight.BarRow
import com.ericflo.winnow.ui.insight.Fact
import com.ericflo.winnow.ui.insight.InsightCard
import com.ericflo.winnow.ui.insight.Note
import com.ericflo.winnow.ui.insight.RateBar
import com.ericflo.winnow.ui.insight.ago
import com.ericflo.winnow.ui.insight.count
import com.ericflo.winnow.ui.insight.f2
import com.ericflo.winnow.ui.insight.pct
import com.ericflo.winnow.ui.metrics.Mine
import com.ericflo.winnow.ui.metrics.computeMine
import java.text.DateFormat
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the overview shows: the model as it stands, who agrees with whom, and who decides. */
data class Overview(
    val fit: ModelFitEntity?,
    /** The version verdicts record ("winnow-local-1·7e5e4c"). */
    val version: String,
    val agreement: Agreement3,
    val weekly: List<WeekAgreement>,
    val deciders: DeciderCounts,
    /** The classifier service as the user knows it, and whether one is set up. */
    val provider: String,
    val serviceOn: Boolean,
    val learnLive: Boolean,
    /** How much one of the service's labels counts against one of the user's, as the model is fitted. */
    val weight: Double,
    /** How the personal layer is fitted, and the Lab model in use, if one is. */
    val personalEpochs: Int = com.ericflo.winnow.classifier.local.Personalizer.EPOCHS,
    val personalStep: Double = com.ericflo.winnow.classifier.local.Personalizer.LEARNING_RATE,
    val personalL2: Double = com.ericflo.winnow.classifier.local.Personalizer.L2,
    val labModel: String? = null,
    val labAutoRetrain: Boolean = true,
    /** Backlog runs, how many carried the user's labels as examples, and their answers on texts asked about again. */
    val runs: Int,
    val runsWithExamples: Int,
    val maxExamples: Int,
    val askedAgain: Int,
    val changedAgain: Int,
)

/** The model's history: every fit, newest first. */
data class Inside(val learned: Map<Category, List<ModelInspector.Learned>>, val taughtTexts: Int)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModelViewModel(private val container: AppContainer) : ViewModel() {
    val overview: StateFlow<Overview?> = combine(
        container.verdictDao.observeAll(),
        container.runDao.observeAll(),
        container.fitDao.observeAll(),
        container.settings.settings,
    ) { verdicts, runs, fits, settings -> Quad(verdicts, runs, fits, settings) }
        .debounce(SETTLE_MILLIS)
        .mapLatest { (verdicts, runs, fits, settings) ->
            val answers = container.runDao.allAnswers()
            val model = container.learner.classifier()
            val again = answers.filter { it.previous != null }
            Overview(
                fit = model.fit?.let { f -> fits.firstOrNull { it.fit == f } },
                version = model.version,
                agreement = ModelInsight.agreement(verdicts, answers),
                weekly = ModelInsight.weekly(verdicts),
                deciders = ModelInsight.deciders(verdicts, ModelInsight.windowStart(WINDOW_DAYS)),
                provider = settings.provider.label.substringBefore(" ("),
                serviceOn = settings.provider != ProviderKind.ON_DEVICE && container.classifiers.provider(settings) != null,
                learnLive = settings.learnFromProvider,
                weight = settings.providerWeight,
                personalEpochs = settings.personalEpochs,
                personalStep = settings.personalStep,
                personalL2 = settings.personalL2,
                labModel = settings.labModel,
                labAutoRetrain = settings.labAutoRetrain,
                runs = runs.size,
                runsWithExamples = runs.count { it.examples > 0 },
                maxExamples = runs.maxOfOrNull { it.examples } ?: 0,
                askedAgain = again.size,
                changedAgain = again.count { it.previous != it.category },
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** How the model does on the user's labels, cross-validated (see MetricsScreen), redone as they label. */
    val mine: StateFlow<Mine?> = container.correctionDao.observeAll()
        .debounce(SETTLE_MILLIS)
        .mapLatest { rows ->
            val job = kotlinx.coroutines.currentCoroutineContext()
            computeMine(rows, container.settings.current().providerWeight) { !job.isActive }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val fits: StateFlow<List<ModelFitEntity>?> = container.fitDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Scoring models on the user's labels (see the Evaluate tab). */
    val evals = container.evaluations

    /** Models the user designs and trains here (see ModelLab and the Lab tab). */
    val lab = container.modelLab

    private val _draft = MutableStateFlow(com.ericflo.winnow.classifier.local.Recipe.PRESETS[2].second)
    /** The model being designed. */
    val draft: StateFlow<com.ericflo.winnow.classifier.local.Recipe> = _draft.asStateFlow()
    private val _draftName = MutableStateFlow(com.ericflo.winnow.classifier.local.Recipe.PRESETS[2].first)
    val draftName: StateFlow<String> = _draftName.asStateFlow()

    fun setDraft(recipe: com.ericflo.winnow.classifier.local.Recipe, name: String) {
        _draft.value = recipe
        _draftName.value = name
    }

    fun editDraft(change: (com.ericflo.winnow.classifier.local.Recipe) -> com.ericflo.winnow.classifier.local.Recipe) {
        _draft.value = change(_draft.value)
    }

    fun setDraftName(name: String) {
        _draftName.value = name
    }

    /** Keeps the design as a model, then trains and scores it. */
    fun trainDraft() {
        val entry = lab.create(_draftName.value, _draft.value)
        lab.trainAndScore(entry.id)
    }

    fun trainEntry(id: String) = lab.trainAndScore(id)

    fun useLab(id: String?) {
        viewModelScope.launch { lab.use(id) }
    }

    /** Trains the model in use again, on the phone, with everything taught so far. */
    fun retrainNow() = lab.retrainInUse()

    fun setLabAutoRetrain(on: Boolean) {
        viewModelScope.launch { container.settings.update { it.copy(labAutoRetrain = on) } }
    }

    /** Asking the service about the user's labeled texts twice (see ExamplesExperiment). */
    val experiment = container.examplesExperiment

    private val _experimentPlan = MutableStateFlow<com.ericflo.winnow.classify.ExamplesExperiment.Plan?>(null)
    val experimentPlan: StateFlow<com.ericflo.winnow.classify.ExamplesExperiment.Plan?> = _experimentPlan.asStateFlow()

    fun planExperiment() {
        viewModelScope.launch { _experimentPlan.value = runCatching { experiment.plan() }.getOrNull() }
    }

    /** The texts a kept experiment asked about whose answer changed between the two ways of asking. */
    suspend fun experimentTrials(at: Long): List<com.ericflo.winnow.classify.Trial> = withContext(Dispatchers.IO) {
        val both = container.evalDao.observeAll().first().filter { it.at == at }
        val plain = both.firstOrNull { it.model == com.ericflo.winnow.classify.ExamplesExperiment.MODEL_PLAIN } ?: return@withContext emptyList()
        val with = both.firstOrNull { it.model == com.ericflo.winnow.classify.ExamplesExperiment.MODEL_EXAMPLES } ?: return@withContext emptyList()
        com.ericflo.winnow.classify.ExamplesExperiment.trialsOf(container.evalDao.items(plain.id), container.evalDao.items(with.id))
    }

    /** The words of [keys], for showing what was asked. */
    suspend fun textsOf(keys: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        MessageTexts(container.appContext).of(keys).mapValues { it.value.body }
    }

    /** Keeps the fit in use now, to score it later against what comes after (see ModelKeeper). */
    fun keepCurrent() {
        viewModelScope.launch { container.modelKeeper.keepCurrent() }
    }

    /** Fits the model with the service's labels at [weight] from now on (see Learner.useProviderWeight). */
    fun useWeight(weight: Double) {
        viewModelScope.launch { container.learner.useProviderWeight(weight) }
    }

    fun forgetKept(fit: String) {
        viewModelScope.launch { container.modelKeeper.forget(fit) }
    }

    private val _inside = MutableStateFlow<Inside?>(null)
    val inside: StateFlow<Inside?> = _inside.asStateFlow()

    /** Works out what the user's teaching changed most, once: it reads every taught text back. */
    fun loadInside() {
        if (_inside.value != null) return
        viewModelScope.launch {
            _inside.value = withContext(Dispatchers.IO) {
                val model = container.learner.classifier()
                val keys = container.correctionDao.all().mapNotNull { it.messageKey }.distinct()
                val texts = MessageTexts(container.appContext).of(keys).values
                    .filter { it.address != null }
                    .map { InboundMessage(it.address!!, it.body) }
                withContext(Dispatchers.Default) { Inside(ModelInspector.learned(model, texts), texts.size) }
            }
        }
    }

    private val _reading = MutableStateFlow<ModelInspector.Reading?>(null)
    val reading: StateFlow<ModelInspector.Reading?> = _reading.asStateFlow()

    /** How the model reads [text], as if from a stranger (or someone the user has texted, [known]). */
    fun read(text: String, known: Boolean) {
        viewModelScope.launch {
            _reading.value = if (text.isBlank()) null else withContext(Dispatchers.Default) {
                ModelInspector.read(container.learner.classifier(), InboundMessage("+15555550100", text, senderInContacts = false, userHasMessagedSender = known))
            }
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private companion object {
        const val SETTLE_MILLIS = 400L
        const val WINDOW_DAYS = 30L
    }
}

private enum class ModelTab(val label: String) { OVERVIEW("Overview"), EVALUATE("Evaluate"), LAB("Lab"), INSIDE("Inside"), HISTORY("History") }

/**
 * Winnow's on-device model, opened up: what it learned from, how it does on the user's own labels,
 * how it, the classifier service and the user line up, who decides their texts, how it's built,
 * what their teaching changed in it, how it reads any text, and every fit it's had.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(
    viewModel: ModelViewModel,
    onBack: () -> Unit,
    onOpenMetrics: () -> Unit,
    onOpenRuns: () -> Unit,
    onOpenTrain: () -> Unit,
    onOpenThread: (Long, List<String>) -> Unit = { _, _ -> },
    /** The tab to open on, by name ("lab"); the first when it names none. */
    startTab: String = "",
    /** When [startTab] was asked for: asked again while this is open, it switches to it. */
    tabAskedAt: Long = 0,
) {
    var tab by rememberSaveable { mutableIntStateOf(ModelTab.entries.indexOfFirst { it.name.equals(startTab, ignoreCase = true) }.coerceAtLeast(0)) }
    // Each request once: a rotation recomposes this, and mustn't undo the user's own choice of tab since.
    var tabAskedHandled by rememberSaveable { androidx.compose.runtime.mutableLongStateOf(tabAskedAt) }
    androidx.compose.runtime.LaunchedEffect(tabAskedAt) {
        if (tabAskedAt == tabAskedHandled) return@LaunchedEffect
        tabAskedHandled = tabAskedAt
        ModelTab.entries.indexOfFirst { it.name.equals(startTab, ignoreCase = true) }.takeIf { it >= 0 }?.let { tab = it }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    // The tab shows how the work on it went: a notification saying so has done its job.
    androidx.compose.runtime.LaunchedEffect(tab) { com.ericflo.winnow.classify.WorkService.clearFinished(context, ModelTab.entries[tab].name.lowercase()) }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Winnow's model") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                )
                androidx.compose.material3.PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                    ModelTab.entries.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t.label) }) }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            when (ModelTab.entries[tab]) {
                ModelTab.OVERVIEW -> overview(viewModel, onOpenMetrics, onOpenRuns, onOpenTrain)
                ModelTab.EVALUATE -> evaluate(viewModel, onOpenThread)
                ModelTab.LAB -> lab(viewModel, onOpenThread)
                ModelTab.INSIDE -> inside(viewModel)
                ModelTab.HISTORY -> history(viewModel)
            }
        }
    }
}

private fun LazyListScope.loading(text: String) {
    item("loading") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            CircularProgressIndicator(Modifier.padding(end = 12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun LazyListScope.overview(viewModel: ModelViewModel, onOpenMetrics: () -> Unit, onOpenRuns: () -> Unit, onOpenTrain: () -> Unit) {
    item("fit") {
        val o by viewModel.overview.collectAsStateWithLifecycle()
        o?.let { FitCard(it) }
    }
    item("mine") {
        val mine by viewModel.mine.collectAsStateWithLifecycle()
        MineCard(mine, onOpenMetrics, onOpenTrain)
    }
    item("agreement") {
        val o by viewModel.overview.collectAsStateWithLifecycle()
        o?.let { AgreementCard(it) } ?: Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
    item("weekly") {
        val o by viewModel.overview.collectAsStateWithLifecycle()
        o?.takeIf { it.weekly.isNotEmpty() }?.let { WeeklyCard(it) }
    }
    item("deciders") {
        val o by viewModel.overview.collectAsStateWithLifecycle()
        o?.let { DecidersCard(it) }
    }
    item("jev") {
        val o by viewModel.overview.collectAsStateWithLifecycle()
        o?.let { LabelsAndServiceCard(it, onOpenRuns) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FitCard(o: Overview) {
    val c = MaterialTheme.colorScheme
    Surface(color = c.primaryContainer, contentColor = c.onPrimaryContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your on-device model", style = MaterialTheme.typography.labelLarge)
            Text(o.version, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            val f = o.fit
            if (f == null) {
                Text("As it ships: nothing taught yet. Every label you give, and every answer ${o.provider} gives, teaches it.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "Fitted ${ago(f.fittedAt)}" + (if (f.millis > 0) " in ${f.millis} ms" else "") + " on this phone, from:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {}, label = { Text("${count(f.userLabels)} your labels") })
                    if (f.corrections > 0) AssistChip(onClick = {}, label = { Text("${count(f.corrections)} your corrections") })
                    if (f.providerLabels > 0) AssistChip(onClick = {}, label = { Text("${count(f.providerLabels)} ${o.provider} backlog answers") })
                    if (f.providerLive > 0) AssistChip(onClick = {}, label = { Text("${count(f.providerLive)} ${o.provider} ${if (f.providerLive == 1) "answer" else "answers"} as texts arrived") })
                }
                Text(
                    "Each of your labels counts fully; each of ${o.provider}'s counts for ${pct(f.providerWeight)} of one of yours, and yours replaces it on the same text. " +
                        "${count(f.buckets)} of its ${count(LocalModel.bundled.buckets)} feature buckets carry something you or ${o.provider} taught it.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (o.labModel != null) {
                Text(
                    "A model you trained in the Lab is sorting your texts in this one's place. This personal layer is still fitted beside it, so you can go back to it at any time.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                if (!o.serviceOn) "No classifier service is set up: the on-device model decides every text no rule does."
                else if (o.learnLive) "It keeps learning from ${o.provider}'s answers as texts arrive (Settings)."
                else "Learning from ${o.provider}'s answers as texts arrive is off (Settings): only your labels and backlog runs teach it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MineCard(mine: Mine?, onOpenMetrics: () -> Unit, onOpenTrain: () -> Unit) {
    InsightCard("On your own labels", subtitle = "Each of your labeled texts scored by the model refit without its conversation: how it does on texts like yours it hasn't seen") {
        val m = mine?.metrics
        when {
            mine == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp))
                Text("Scoring it on your labels…", style = MaterialTheme.typography.bodyMedium)
            }
            m == null -> {
                Text(
                    "Needs 20 labeled texts in at least two categories: you have ${count(mine.labels)} in ${mine.categories}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onOpenTrain, contentPadding = PaddingValues(0.dp)) { Text("Label some in Train Winnow") }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                    Column {
                        Text(pct(m.accuracy), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                        Text("in the category you gave", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column {
                        Text(f2(m.macroF1), style = MaterialTheme.typography.titleLarge)
                        Text("macro F1", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column {
                        Text(pct(m.unwanted.operatingPoint.falsePositiveRate), style = MaterialTheme.typography.titleLarge)
                        Text("wanted ones filtered", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Note("From ${count(m.examples)} labeled texts. Every chart (ROC, precision and recall, calibration, each category, the confusion matrix) is under How accurate is Winnow.")
                TextButton(onClick = onOpenMetrics, contentPadding = PaddingValues(0.dp)) { Text("All the charts") }
            }
        }
    }
}

@Composable
private fun AgreementCard(o: Overview) {
    val a = o.agreement
    val c = MaterialTheme.colorScheme
    InsightCard("Who agrees with whom", subtitle = "Each pair only on texts both of them judged. Your labels are the answer key; ${o.provider} and the model can each be wrong.") {
        RateBar("You and ${o.provider}", a.youService.agreed, a.youService.compared, detail = "its answers on texts you've labeled")
        RateBar("You and the on-device model", a.youModel.agreed, a.youModel.compared, color = c.tertiary, detail = "its own opinion when it judged them, before learning from you")
        RateBar("The on-device model and ${o.provider}", a.modelService.agreed, a.modelService.compared, color = c.secondary, detail = "its opinion beside ${o.provider}'s answer, before learning from it")
        if (a.modelWithYouAgainstService + a.modelWithServiceAgainstYou > 0) {
            Text(
                "Where all three judged a text and disagreed: the model sided with you against ${o.provider} ${count(a.modelWithYouAgainstService)} times, " +
                    "and with ${o.provider} against you ${count(a.modelWithServiceAgainstYou)} times.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Note(
            "The model's opinions were kept with each decision from this version on, and with every backlog-run answer; " +
                "older decisions only say who decided. The more you label, the more these mean.",
        )
    }
}

@Composable
private fun WeeklyCard(o: Overview) {
    val format = DateTimeFormatter.ofPattern("MMM d")
    InsightCard("Is it coming to sort like ${o.provider}?", subtitle = "Week by week, on texts ${o.provider} decided as they arrived: how often the model's own opinion was the same") {
        o.weekly.takeLast(12).forEach { w ->
            RateBar("Week of ${w.start.format(format)}", w.pair.agreed, w.pair.compared, color = MaterialTheme.colorScheme.secondary)
        }
        Note("Agreeing with ${o.provider} isn't the goal in itself: where ${o.provider} and your labels differ, your labels win.")
    }
}

@Composable
private fun DecidersCard(o: Overview) {
    val d = o.deciders
    InsightCard("Who decided your texts", subtitle = "The last 30 days, as each text arrived: ${count(d.total)} texts") {
        if (d.total == 0) {
            Note("Nothing decided in the last 30 days.")
            return@InsightCard
        }
        val rows = listOfNotNull(
            "A rule on this phone" to d.rule,
            "${o.provider}" to d.service,
            "Model, sure enough not to ask" to d.modelSure,
            "Model, ${o.provider} didn't answer" to d.modelFallback,
            "Model, nothing could be sent" to d.modelKept,
            "Model, no service set up" to d.modelOnly,
            ("Model, decided before Winnow kept why" to d.modelUnknown).takeIf { d.modelUnknown > 0 },
            ("Keyword fallback" to d.keywords).takeIf { d.keywords > 0 },
        ).filter { it.second > 0 || it.first == "${o.provider}" }
        val max = rows.maxOf { it.second }
        rows.forEach { (label, n) -> com.ericflo.winnow.ui.insight.StackedBar(label, n, max, d.total) }
        Note(
            "Rules: your contacts, people you've texted, codes, your sender rules and filtered words, all decided on the phone. " +
                "You've since labeled or corrected ${count(d.youSince)} of these; your say is what stands.",
        )
    }
}

@Composable
private fun LabelsAndServiceCard(o: Overview, onOpenRuns: () -> Unit) {
    InsightCard("Do your labels make ${o.provider} better?", subtitle = "The honest answer") {
        Text(
            "Not ${o.provider} itself: it's a model run by its provider, and nothing Winnow does trains it. Your labels reach it one way only: " +
                "in a backlog run (or Ask again), up to ${com.ericflo.winnow.classify.Bootstrap.EXAMPLES_PER_CATEGORY} of your labeled texts per category go with each question, " +
                "as examples of how you sort. As texts arrive, ${o.provider} is asked the plain question, without them.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "What your labels do train is the model on this phone: fully, every time you label.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (o.runs > 0) {
            Text(
                "So far: ${count(o.runs)} runs, ${count(o.runsWithExamples)} of them with your labels as examples (up to ${count(o.maxExamples)} per question)." +
                    if (o.askedAgain > 0) " Asked again about ${count(o.askedAgain)} texts, it changed its answer on ${count(o.changedAgain)}." else "",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Note("Whether the examples change its answers can be measured on your own labels: ask it about them with and without examples, and compare.")
        TextButton(onClick = onOpenRuns, contentPadding = PaddingValues(0.dp)) { Text("Every run, and every answer") }
    }
}

private fun LazyListScope.inside(viewModel: ModelViewModel) {
    item("built") {
        val o by viewModel.overview.collectAsStateWithLifecycle()
        BuiltCard(o?.weight ?: Learner.PROVIDER_WEIGHT)
    }
    item("learned") {
        androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.loadInside() }
        val inside by viewModel.inside.collectAsStateWithLifecycle()
        LearnedCard(inside)
    }
    item("try") { TryCard(viewModel) }
}

@Composable
private fun BuiltCard(weight: Double) {
    val model = LocalModel.bundled
    // Read from the APK the first time: off the main thread.
    val shipped by androidx.compose.runtime.produceState<ClassifierMetrics?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { ClassifierMetrics.bundled }.getOrNull() }
    }
    val policy = ActionPolicy()
    InsightCard("How it's built", subtitle = "Everything it is, with nothing left out") {
        Fact("Kind", "linear", "A softmax regression: one weight per feature bucket and category, plus a lean per category. Each category's score is the sum of its weights for the text's features; the odds come from those scores.")
        Fact("Categories", "${model.classes.size}", model.classes.joinToString(", ") { Category.fromKey(it)?.label ?: it })
        Fact("Feature buckets", count(model.buckets), "Words and word pairs, hashed into buckets (two can share one), plus named signals: links and where they point, money, numbers to call, shouting, emoji, the kind of sender, opt-outs, greetings, deadlines, letters from another alphabet. Featurizer version ${Featurizer.VERSION}.")
        Fact("Size", "${count(model.buckets * model.classes.size)} weights", "Stored as 8-bit numbers, one scale per category: about ${count(model.buckets * model.classes.size / 1024)} KB, shipped inside Winnow. It reads the whole text, unredacted, because nothing leaves the phone.")
        Fact(
            "Temperature",
            f2(model.temperature.toDouble()),
            "Scores are divided by this before they become odds, chosen so its odds match how often it's right on its test texts" +
                (shipped?.let { " (expected calibration error there: ${f2(it.ece)})." } ?: "."),
        )
        shipped?.let { m ->
            Fact("Trained on", "${count(m.examples)} texts", "Before shipping: ${m.method} Accuracy ${pct(m.accuracy)}, macro F1 ${f2(m.macroF1)}" + (m.evaluation?.let { e -> "; on ${e.examples} more it never trained on, ${pct(e.accuracy)}." } ?: "."))
        }
        Fact(
            "What you teach it",
            "a layer on top",
            "Your labels and the service's answers don't change those weights: they fit a sparse layer of adjustments, only for buckets that taught texts had, added to the shipped weights. " +
                "Fitted from scratch on every label: ${Personalizer.EPOCHS} passes, step ${Personalizer.LEARNING_RATE} with AdaGrad, a pull of ${Personalizer.L2} toward changing nothing, so texts unlike what you taught barely move. " +
                if (weight <= 0) "Your labels are pulled all the way to their category; a service's labels are left out (you set their weight to 0)."
                else "Your labels are pulled all the way to their category; a service's answer, counting ${pct(weight)}, only to ${pct(Personalizer.LIGHT_FLOOR + (1 - Personalizer.LIGHT_FLOOR) * weight.coerceAtMost(1.0))}, so yours win.",
        )
        Fact("When it acts alone", ">= ${pct(policy.onDeviceMinConfidence)}", "It filters a text only when it's at least this sure (a service, ${pct(policy.minConfidence)}), and never a spam text with nothing to hook you with. With “decide on this phone when sure” on, it skips the service at 95%.")
        Fact("Fit kept", "between runs", "The last fit is saved, named by what it learned from, and used again until something it learned from changes.")
    }
}

@Composable
private fun LearnedCard(inside: Inside?) {
    InsightCard("What your teaching changed most", subtitle = "For each category, the features its learned layer pulls hardest toward it, named from the texts that taught them") {
        if (inside == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp))
                Text("Reading back the taught texts…", style = MaterialTheme.typography.bodyMedium)
            }
            return@InsightCard
        }
        if (inside.learned.values.all { it.isEmpty() }) {
            Note("Nothing learned yet: label some texts, and what they taught shows here.")
            return@InsightCard
        }
        Category.entries.forEach { category ->
            val top = inside.learned[category].orEmpty()
            if (top.isEmpty()) return@forEach
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryDot(category)
                Spacer(Modifier.width(8.dp))
                Text(category.label, style = MaterialTheme.typography.titleSmall)
            }
            Text(top.joinToString("  ·  ") { "${it.name} +${f2(it.weight)}" }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 32.dp))
        }
        Note("Named from ${count(inside.taughtTexts)} taught texts still on the phone. A bucket is a hash of a feature, so one no taught text still has can't be named, and isn't listed.")
    }
}

@Composable
private fun TryCard(viewModel: ModelViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    var known by rememberSaveable { mutableStateOf(false) }
    val reading by viewModel.reading.collectAsStateWithLifecycle()
    InsightCard("Try a text", subtitle = "How the model reads anything you type: nothing is sent anywhere") {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; viewModel.read(it, known) },
            label = { Text("A text, as if it arrived") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("From someone you've texted", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = known, onCheckedChange = { known = it; viewModel.read(text, it) })
        }
        val r = reading ?: return@InsightCard
        Text("Its odds: as it ships → with what you taught it", style = MaterialTheme.typography.labelLarge)
        Category.entries.forEach { c ->
            val before = r.shipped[c] ?: 0.0
            val after = r.taught[c] ?: 0.0
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryDot(c)
                Spacer(Modifier.width(8.dp))
                Text(c.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("${pct(before)} → ${pct(after)}", style = MaterialTheme.typography.titleSmall, fontWeight = if (after == r.taught.values.max()) FontWeight.Bold else FontWeight.Normal)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("What pulled where", style = MaterialTheme.typography.labelLarge)
        r.features.forEach { p ->
            Row(verticalAlignment = Alignment.Top) {
                Text(p.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    "${p.toward.label} ${signed(p.shipped)}" + if (p.learned != 0.0) " ${signed(p.learned)} taught" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Note("Each feature's pull toward the category it favors most, in the model's own units, from the shipped weights and from what was taught. Strongest first.")
    }
}

private fun signed(x: Double) = (if (x >= 0) "+" else "") + String.format(java.util.Locale.US, "%.2f", x)

private fun LazyListScope.history(viewModel: ModelViewModel) {
    item("about") {
        Note(
            "Every fit of the on-device model, newest first: what it learned from, and how long fitting took. Each verdict it made names its fit, so any decision can be traced to what taught the model then. " +
                "A kept fit's learned layer is saved, so it can be scored on the labels you make after it (Evaluate). One is kept after every Train round and every backlog run.",
        )
    }
    item("keep") {
        val o by viewModel.overview.collectAsStateWithLifecycle()
        if (o?.fit?.kept == false) OutlinedButtonRow("Keep the model as it is now", viewModel::keepCurrent)
    }
    item("fits") {
        val fits by viewModel.fits.collectAsStateWithLifecycle()
        val list = fits
        if (list == null) {
            CircularProgressIndicator()
        } else if (list.isEmpty()) {
            Note("No fits yet: the first comes with your first label, or a service's first answer.")
        } else {
            val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list.take(200).forEach { f ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row {
                                Text((f.name ?: "fit ${f.fit}") + if (f.kept) " · kept" else "", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Text(format.format(Date(f.fittedAt)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                listOfNotNull(
                                    "${count(f.userLabels)} your labels",
                                    f.corrections.takeIf { it > 0 }?.let { "${count(it)} corrections" },
                                    f.providerLabels.takeIf { it > 0 }?.let { "${count(it)} backlog answers" },
                                    f.providerLive.takeIf { it > 0 }?.let { "${count(it)} live answers" },
                                    "${count(f.buckets)} buckets",
                                    if (f.millis > 0) "${f.millis} ms" else "loaded as kept",
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (f.kept) {
                                var letting by androidx.compose.runtime.remember(f.fit) { androidx.compose.runtime.mutableStateOf(false) }
                                if (letting) {
                                    androidx.compose.material3.AlertDialog(
                                        onDismissRequest = { letting = false },
                                        title = { Text("Let ${f.name ?: "fit ${f.fit}"} go?") },
                                        text = { Text("Its saved copy goes, so it can't be scored or compared again. Its line here stays, and the model Winnow uses now doesn't change.") },
                                        confirmButton = { TextButton(onClick = { letting = false; viewModel.forgetKept(f.fit) }) { Text("Let it go") } },
                                        dismissButton = { TextButton(onClick = { letting = false }) { Text("Keep it") } },
                                    )
                                }
                                TextButton(onClick = { letting = true }, contentPadding = PaddingValues(0.dp)) { Text("Let it go") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutlinedButtonRow(label: String, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick = onClick) { Text(label) }
}
