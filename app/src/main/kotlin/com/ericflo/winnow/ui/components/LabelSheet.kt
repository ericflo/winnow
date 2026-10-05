package com.ericflo.winnow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ericflo.winnow.classifier.message.Action
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.ui.theme.categoryColors

/**
 * "Label as": the seven categories, each with what it means and where it goes, so the user
 * knows what a tap will do. One tap labels; there's no confirm (an Undo follows instead).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelSheet(
    title: String,
    /** The label it has now, ticked; null for none. */
    current: Category?,
    /** Where each category files a message, by the user's own settings. */
    actionFor: (Category) -> Action,
    onPick: (Category) -> Unit,
    onDismiss: () -> Unit,
) {
    // All the way open: the last categories (scam, spam) are the ones most often picked.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
            Text(
                "Winnow learns from every label, right here on your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            val colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            Category.entries.forEach { category ->
                ListItem(
                    leadingContent = { CategoryDot(category) },
                    headlineContent = { Text(category.label) },
                    supportingContent = { Text("${hint(category)} · ${destination(actionFor(category))}") },
                    trailingContent = { if (category == current) Icon(Icons.Filled.Check, contentDescription = "Labeled now") },
                    colors = colors,
                    modifier = Modifier.selectable(selected = category == current, role = Role.RadioButton) { onPick(category) },
                )
            }
        }
    }
}

/** A category's color, as a dot. */
@Composable
fun CategoryDot(category: Category?, modifier: Modifier = Modifier) {
    val (container, content) = categoryColors(category)
    Box(modifier.size(24.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(8.dp).background(content, CircleShape))
    }
}

/** What a category means, in a few words. */
fun hint(category: Category): String = when (category) {
    Category.PERSONAL -> "Someone writing to you"
    Category.TRANSACTIONAL -> "Codes, orders, bills, appointments"
    Category.MARKETING -> "Ads and offers from a business"
    Category.POLITICAL -> "Campaigns, donations, polls"
    Category.PHISHING -> "Pretends to be a company or bank"
    Category.SCAM -> "A stranger working an angle"
    Category.SPAM -> "Junk nobody asked for"
}

/** Where a message with [action] goes. */
fun destination(action: Action): String = when (action) {
    Action.ALLOW -> "Inbox"
    Action.SILENCE -> "Arrives quietly"
    Action.FILTER -> "Filtered"
}
