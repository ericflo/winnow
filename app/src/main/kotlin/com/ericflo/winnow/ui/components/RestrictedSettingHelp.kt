package com.ericflo.winnow.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ericflo.winnow.WinnowApp

/**
 * Shown when asking to be the default SMS app didn't work. On Android 15 and later, an app
 * installed from a browser can't take the SMS role until the user allows restricted settings
 * for it in App info; Android only says "Restricted setting", without the way out.
 */
@Composable
fun RestrictedSettingHelp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val refused by (context.applicationContext as WinnowApp).container.defaultRefused.collectAsStateWithLifecycle()
    if (!refused) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Winnow isn't your SMS app yet", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
        Text(
            "If Android said \"App was denied access\": open App info, tap ⋮ at the top right, choose " +
                "\"Allow restricted settings\", then come back and tap Set as default again. Android asks this of apps installed from a browser.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = {
            val info = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            runCatching { context.startActivity(info) }
        }) { Text("Open App info") }
    }
}
