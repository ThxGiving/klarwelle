package com.px6.radio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.px6.radio.R
import com.px6.radio.model.Band
import com.px6.radio.model.Station
import com.px6.radio.ui.theme.appColors
import com.px6.radio.ui.theme.touchMin

/**
 * The one place internet stations are managed — reused by the main-screen manual view (band IP has
 * no frequency to tune, so that area becomes search-and-add) and by a settings page.
 *
 * Search hits come from radio-browser via the ViewModel; adding one drops it into the shared station
 * list (band IP) where it plays like any other station. The examples (London/Tbilisi) start here too
 * and can be removed. No hardcoded catalogue — the user owns this list.
 */
@Composable
fun InternetPanel(
    stations: List<Station>,
    results: List<Station>,
    searching: Boolean,
    onSearch: (String) -> Unit,
    onAdd: (Station) -> Unit,
    onRemove: (String) -> Unit,
    onPlay: (Station) -> Unit = {},
    onAddManual: ((String, String) -> Unit)? = null,
    fontScale: Float = 1f,
) {
    val focus = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var showManualDialog by remember { mutableStateOf(false) }

    if (showManualDialog && onAddManual != null) {
        ManualAddDialog(
            onAdd = { n, u -> onAddManual(n, u); showManualDialog = false },
            onDismiss = { showManualDialog = false },
            fontScale = fontScale,
        )
    }
    // Debounced live search: type and results follow, without a button.
    LaunchedEffect(query) {
        if (query.trim().length >= 2) {
            kotlinx.coroutines.delay(350)
            onSearch(query)
        } else {
            onSearch("")
        }
    }
    val have = stations.map { it.id }.toSet()

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        SearchField(
            query = query,
            onQuery = { query = it },
            onSubmit = { onSearch(query) },
            fontScale = fontScale,
        )

        Spacer(Modifier.size(12.dp))

        // Search results (add), when a query is active. Otherwise the saved list is the whole page.
        if (query.trim().length >= 2) {
            SectionLabel(if (searching) "Suche läuft…" else "Ergebnisse (${results.size})", fontScale)
            if (results.isEmpty() && !searching) {
                Hint("Nichts gefunden. Anderen Namen versuchen — z. B. BBC, LBC, Tbilisi.", fontScale)
            }
            results.take(25).forEach { st ->
                StationRow(
                    station = st,
                    trailing = if (st.id in have) "✓" else "＋",
                    trailingActive = st.id in have,
                    onTrailing = { if (st.id !in have) { onAdd(st); focus.clearFocus() } },
                    onTap = { if (st.id !in have) { onAdd(st); focus.clearFocus() } },
                    fontScale = fontScale,
                )
            }
            Spacer(Modifier.size(14.dp))
        }

        // "Meine Sender" header with a compact "+" button (settings only) that opens the add modal.
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("Meine Sender (${stations.size})", fontScale, Modifier.weight(1f))
            if (onAddManual != null && query.trim().length < 2) {
                Box(
                    Modifier.size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(appColors.panel)
                        .border(1.dp, appColors.line, RoundedCornerShape(10.dp))
                        .clickable { focus.clearFocus(); showManualDialog = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("＋", color = appColors.text, fontSize = (22 * fontScale).sp)
                }
            }
        }
        if (stations.isEmpty()) {
            Hint(stringResource(R.string.internet_empty_hint), fontScale)
        }
        // Group by city (station.ensemble) with a heading per group, so the catalogue reads by city.
        val byCity = stations
            .groupBy { it.ensemble?.takeIf { it.isNotBlank() } ?: stringResource(R.string.internet_group_other) }
            .toSortedMap()
        byCity.forEach { (city, list) ->
            Text(
                city.uppercase(),
                color = appColors.accent, fontSize = (12 * fontScale).sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp, start = 2.dp),
            )
            list.sortedBy { it.name.lowercase() }.forEach { st ->
                StationRow(
                    station = st,
                    trailing = "✕",
                    trailingActive = false,
                    onTrailing = { onRemove(st.id) },
                    onTap = { onPlay(st); focus.clearFocus() },
                    fontScale = fontScale,
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    fontScale: Float,
) {
    val focus = LocalFocusManager.current
    Box(
        Modifier.fillMaxWidth()
            .heightIn(min = touchMin)
            .clip(RoundedCornerShape(10.dp))
            .background(appColors.panel)
            .border(1.dp, appColors.line, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("⌕", color = appColors.muted, fontSize = (20 * fontScale).sp)
            Spacer(Modifier.size(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "Sender suchen (Stadt oder Name)…",
                        color = appColors.muted, fontSize = (17 * fontScale).sp, maxLines = 1,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = appColors.text, fontSize = (17 * fontScale).sp),
                    cursorBrush = SolidColor(appColors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    // The Search/Enter key runs the search AND drops focus, so the keyboard closes.
                    keyboardActions = KeyboardActions(onSearch = { onSubmit(); focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                // Clear the text and close the keyboard in one tap.
                Text(
                    "✕", color = appColors.muted, fontSize = (18 * fontScale).sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .clickable { onQuery(""); focus.clearFocus() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            } else {
                // A visible "close keyboard" affordance while typing with an empty box.
                Text(
                    "⌄", color = appColors.muted, fontSize = (20 * fontScale).sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .clickable { focus.clearFocus() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StationRow(
    station: Station,
    trailing: String,
    trailingActive: Boolean,
    onTrailing: () -> Unit,
    onTap: () -> Unit,
    fontScale: Float,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = touchMin)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onTap() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                station.name, color = appColors.text, fontSize = (17 * fontScale).sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                station.subtitle, color = appColors.muted, fontSize = (13 * fontScale).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.size(10.dp))
        Box(
            Modifier.size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (trailingActive) appColors.accentSoft else appColors.panel)
                .border(1.dp, appColors.line, RoundedCornerShape(8.dp))
                .clickable { onTrailing() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                trailing,
                color = if (trailingActive) appColors.accent else appColors.text,
                fontSize = (18 * fontScale).sp,
            )
        }
    }
}

@Composable
private fun ManualAddDialog(
    onAdd: (String, String) -> Unit,
    onDismiss: () -> Unit,
    fontScale: Float,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val valid = url.trim().startsWith("http")
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val cfg = androidx.compose.ui.platform.LocalConfiguration.current
        val w = (cfg.screenWidthDp * 0.8f).dp
        Column(
            Modifier
                // Explicit width: with Compose's platform cap switched off (needed elsewhere so the
                // info panels are not squeezed) a dialog without one sizes to its content, and the
                // fields inside are fillMaxWidth — the result would be arbitrary.
                .widthIn(max = if (w < 640.dp) w else 640.dp)
                .heightIn(max = (cfg.screenHeightDp * 0.94f).dp)
                .clip(RoundedCornerShape(16.dp))
                .background(appColors.bg)
                .border(1.dp, appColors.line, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text(
                stringResource(R.string.internet_add_title),
                color = appColors.text, fontSize = (18 * fontScale).sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(16.dp))
            TextBox(stringResource(R.string.internet_name_placeholder), name, { name = it }, fontScale)
            Spacer(Modifier.size(10.dp))
            TextBox(stringResource(R.string.internet_url_placeholder), url, { url = it }, fontScale)
            Spacer(Modifier.size(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DialogButton(stringResource(R.string.action_cancel), active = false, alwaysEnabled = true, fontScale = fontScale) { onDismiss() }
                Spacer(Modifier.size(10.dp))
                DialogButton(stringResource(R.string.action_add), active = valid, fontScale = fontScale) {
                    if (valid) onAdd(name, url)
                }
            }
        }
    }
}

@Composable
private fun DialogButton(text: String, active: Boolean, fontScale: Float, alwaysEnabled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) appColors.accentSoft else appColors.panel)
            .border(1.dp, if (active) appColors.accentSoftBorder else appColors.line, RoundedCornerShape(10.dp))
            .clickable(enabled = active || alwaysEnabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            color = if (active) appColors.accent else appColors.muted,
            fontSize = (16 * fontScale).sp, fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun TextBox(placeholder: String, value: String, onValue: (String) -> Unit, fontScale: Float) {
    Box(
        Modifier.fillMaxWidth()
            .heightIn(min = touchMin)
            .clip(RoundedCornerShape(10.dp))
            .background(appColors.panel)
            .border(1.dp, appColors.line, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = appColors.muted, fontSize = (16 * fontScale).sp, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = TextStyle(color = appColors.text, fontSize = (16 * fontScale).sp),
            cursorBrush = SolidColor(appColors.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SectionLabel(text: String, fontScale: Float, modifier: Modifier = Modifier) {
    Text(
        text, color = appColors.muted, fontSize = (13 * fontScale).sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(vertical = 6.dp, horizontal = 2.dp),
    )
}

@Composable
private fun Hint(text: String, fontScale: Float) {
    Text(
        text, color = appColors.muted, fontSize = (14 * fontScale).sp,
        modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp),
    )
}
