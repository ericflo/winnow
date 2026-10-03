package com.ericflo.winnow.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.ericflo.winnow.WinnowApp
import com.ericflo.winnow.data.joinAddresses
import com.ericflo.winnow.sms.recipientsOf
import com.ericflo.winnow.ui.theme.WinnowTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val container by lazy { (application as WinnowApp).container }
    private val pendingRoute = MutableStateFlow<ThreadRoute?>(null)

    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        container.refreshAccess()
        requestCompanionPermissions()
    }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        container.refreshAccess()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            WinnowTheme {
                WinnowNavHost(
                    container = container,
                    pendingRoute = pendingRoute,
                    onRouteConsumed = { pendingRoute.value = null },
                    onMakeDefault = ::requestDefaultSmsRole,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        container.refreshAccess()
    }

    private fun requestDefaultSmsRole() {
        val roles = getSystemService(RoleManager::class.java)
        if (roles.isRoleAvailable(RoleManager.ROLE_SMS) && !roles.isRoleHeld(RoleManager.ROLE_SMS)) {
            roleRequest.launch(roles.createRequestRoleIntent(RoleManager.ROLE_SMS))
        } else {
            requestCompanionPermissions()
        }
    }

    /** Contacts power the never-classify-contacts rule; notifications are the point of an SMS app. */
    private fun requestCompanionPermissions() {
        val missing = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_THREAD -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return
                pendingRoute.value = ThreadRoute(intent.getLongExtra(EXTRA_THREAD_ID, -1), address)
            }
            Intent.ACTION_SENDTO, Intent.ACTION_SEND -> {
                val recipients = intent.data?.let(::recipientsOf).orEmpty().ifEmpty { return }
                val body = intent.getStringExtra("sms_body") ?: intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                pendingRoute.value = ThreadRoute(-1, joinAddresses(recipients), body)
            }
        }
    }

    companion object {
        const val ACTION_OPEN_THREAD = "com.ericflo.winnow.OPEN_THREAD"
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_ADDRESS = "address"
    }
}
