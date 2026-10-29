package com.aura.music.feature.actions

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.aura.music.R
import com.aura.music.core.designsystem.components.AuraButton
import com.aura.music.core.designsystem.components.ButtonStyle
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.model.Track

/**
 * Deletes a song's file from the device, always with the user's confirmation: Android 11+ shows its
 * own "allow AURA to delete?" dialog; on Android 10 AURA asks first and Android may ask again for
 * files another app created. [onResult] gets true when the file is gone.
 */
@Composable
fun rememberDeleteTrack(onResult: (Track, Boolean) -> Unit): (Track) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Track?>(null) }
    var confirming by remember { mutableStateOf<Track?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val track = pending ?: return@rememberLauncherForActivityResult
        pending = null
        val allowed = result.resultCode == Activity.RESULT_OK
        // Android 11+ deletes on "Allow"; Android 10 only grants access, so delete now.
        val deleted = allowed && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || deleteNow(context, track.uri.toUri()) == null)
        onResult(track, deleted)
    }

    fun start(track: Track) {
        val uri = track.uri.toUri()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pending = track
            launcher.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender).build())
        } else {
            confirming = track
        }
    }

    confirming?.let { track ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirming = null },
            containerColor = AuraTheme.colors.surfaceElevated,
            shape = Radius.xLarge,
            title = { androidx.compose.material3.Text(stringResource(R.string.delete_title, track.title), style = AuraTheme.type.headline, color = AuraTheme.colors.textPrimary) },
            text = { androidx.compose.material3.Text(stringResource(R.string.delete_body), style = AuraTheme.type.body, color = AuraTheme.colors.textSecondary) },
            confirmButton = {
                AuraButton(stringResource(R.string.delete_confirm), {
                    confirming = null
                    when (val needs = deleteNow(context, track.uri.toUri())) {
                        null -> onResult(track, true)
                        else -> {
                            pending = track
                            launcher.launch(IntentSenderRequest.Builder(needs.userAction.actionIntent.intentSender).build())
                        }
                    }
                })
            },
            dismissButton = { AuraButton(stringResource(R.string.action_cancel), { confirming = null }, style = ButtonStyle.Secondary) },
        )
    }
    return ::start
}

/** Deletes [uri]; returns the exception that asks for the user's permission, or null when done. */
private fun deleteNow(context: Context, uri: Uri): RecoverableSecurityException? = try {
    context.contentResolver.delete(uri, null, null)
    null
} catch (e: RecoverableSecurityException) {
    e
}
