package com.aura.music.feature.common

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aura.music.R
import com.aura.music.core.designsystem.components.EmptyState
import com.aura.music.core.designsystem.components.LoadingState
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.data.media.AudioPermission
import com.aura.music.data.repository.LibraryState

enum class LibraryStatus { Loading, PermissionRequired, Error, Empty, Ready }

fun LibraryState.status(): LibraryStatus = when (this) {
    LibraryState.Loading -> LibraryStatus.Loading
    LibraryState.PermissionRequired -> LibraryStatus.PermissionRequired
    LibraryState.Error -> LibraryStatus.Error
    is LibraryState.Ready -> if (index.isEmpty) LibraryStatus.Empty else LibraryStatus.Ready
}

/**
 * Shows the right state for the library (permission, loading, error, empty) and only
 * renders [content] once there is music to show.
 */
@Composable
fun LibraryGate(
    status: LibraryStatus,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    when (status) {
        LibraryStatus.Ready -> content()
        LibraryStatus.Loading -> LoadingState(modifier)
        LibraryStatus.PermissionRequired -> Centered(modifier) { PermissionRequest(onRefresh) }
        LibraryStatus.Error -> Centered(modifier) {
            EmptyState(
                icon = AuraIcons.Refresh,
                title = stringResource(R.string.library_error_title),
                message = stringResource(R.string.library_error_body),
                actionLabel = stringResource(R.string.action_retry),
                onAction = onRefresh,
            )
        }
        LibraryStatus.Empty -> Centered(modifier) {
            EmptyState(
                icon = AuraIcons.Note,
                title = stringResource(R.string.library_empty_title),
                message = stringResource(R.string.library_empty_body),
                actionLabel = stringResource(R.string.settings_rescan),
                onAction = onRefresh,
            )
        }
    }
}

@Composable
private fun Centered(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** Requests audio access; after a permanent denial, routes the user to system settings instead. */
@Composable
fun PermissionRequest(onGranted: () -> Unit) {
    val context = LocalContext.current
    var deniedOnce by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onGranted() else deniedOnce = true
    }
    EmptyState(
        icon = AuraIcons.Note,
        title = stringResource(R.string.permission_title),
        message = stringResource(if (deniedOnce) R.string.onboarding_denied else R.string.permission_body),
        actionLabel = stringResource(if (deniedOnce) R.string.onboarding_open_settings else R.string.onboarding_grant),
        onAction = {
            if (deniedOnce) context.startActivity(appSettingsIntent(context.packageName)) else launcher.launch(AudioPermission.name)
        },
    )
}

fun appSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
