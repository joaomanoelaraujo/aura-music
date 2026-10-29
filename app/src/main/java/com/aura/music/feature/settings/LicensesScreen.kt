package com.aura.music.feature.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.aura.music.R
import com.aura.music.core.designsystem.components.AuraTopBar
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.navigation.LocalNavigator
import com.aura.music.ui.LocalBottomInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class License(val name: String, val license: String, val assetText: String? = null)

private val licenses = listOf(
    License("Instrument Serif", "SIL Open Font License 1.1", "OFL-InstrumentSerif.txt"),
    License("Manrope", "SIL Open Font License 1.1", "OFL-Manrope.txt"),
    License("AndroidX · Jetpack Compose", "Apache License 2.0"),
    License("AndroidX Media3 (ExoPlayer)", "Apache License 2.0"),
    License("AndroidX Room · DataStore · Navigation · Palette", "Apache License 2.0"),
    License("Dagger Hilt", "Apache License 2.0"),
    License("Coil", "Apache License 2.0"),
    License("Kotlin · kotlinx.coroutines · kotlinx.serialization", "Apache License 2.0"),
)

@Composable
fun LicensesScreen() {
    val navigator = LocalNavigator.current
    Column(Modifier.fillMaxSize()) {
        AuraTopBar(stringResource(R.string.settings_licenses), onBack = { navigator.back() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + Spacing.x6)) {
            items(licenses, key = { it.name }) { LicenseItem(it) }
        }
    }
}

@Composable
private fun LicenseItem(item: License) {
    var expanded by rememberSaveable(item.name) { mutableStateOf(false) }
    val context = LocalContext.current
    val text by produceState<String?>(null, expanded) {
        if (expanded && item.assetText != null) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.assets.open(item.assetText).bufferedReader().use { it.readText() } }.getOrNull()
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = item.assetText != null, role = Role.Button) { expanded = !expanded }
            .animateContentSize()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.x3),
    ) {
        Text(item.name, style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textPrimary)
        Text(item.license, style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary)
        if (expanded) {
            text?.let {
                Text(it, style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textTertiary, modifier = Modifier.padding(top = Spacing.x3))
            }
        }
    }
}
