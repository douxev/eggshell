package com.douxev.eggshell.ui.pdf

import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.R
import com.douxev.eggshell.data.PdfReportExporter

@HiltViewModel
class PdfExportViewModel @Inject constructor(
    private val exporter: PdfReportExporter,
) : ViewModel() {

    data class State(
        val options: PdfReportExporter.Options = PdfReportExporter.Options(),
        val generating: Boolean = false,
        val error: String? = null,
        val generatedFile: java.io.File? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun updateOptions(transform: (PdfReportExporter.Options) -> PdfReportExporter.Options) {
        _state.value = _state.value.copy(options = transform(_state.value.options))
    }

    fun generate() {
        viewModelScope.launch {
            _state.value = _state.value.copy(generating = true, error = null)
            runCatching { exporter.generate(_state.value.options) }
                .onSuccess { _state.value = _state.value.copy(generating = false, generatedFile = it) }
                .onFailure { _state.value = _state.value.copy(generating = false, error = it.message) }
        }
    }

    fun consumeGeneratedFile(): java.io.File? {
        val f = _state.value.generatedFile
        _state.value = _state.value.copy(generatedFile = null)
        return f
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PdfExportScreen(
    onBack: () -> Unit,
    vm: PdfExportViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current

    // Whenever a fresh PDF lands, hand it off to the system share sheet.
    val pendingFile = state.generatedFile
    if (pendingFile != null) {
        val file = vm.consumeGeneratedFile() ?: pendingFile
        val uri = FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, null))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pdf_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { IntroCard() }

            item {
                Text(
                    stringResource(R.string.pdf_section_period),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val periods = listOf(
                        1 to R.string.pdf_period_1m,
                        3 to R.string.pdf_period_3m,
                        6 to R.string.pdf_period_6m,
                        120 to R.string.pdf_period_all,
                    )
                    periods.forEach { (months, labelRes) ->
                        FilterChip(
                            selected = state.options.periodMonths == months,
                            onClick = {
                                vm.updateOptions { it.copy(periodMonths = months) }
                            },
                            label = { Text(stringResource(labelRes)) },
                            shape = RoundedCornerShape(50),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        )
                    }
                }
            }

            item {
                Text(
                    stringResource(R.string.pdf_section_include),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        IncludeRow(
                            icon = Icons.Filled.Medication,
                            title = stringResource(R.string.pdf_inc_meds),
                            sub = stringResource(R.string.pdf_inc_meds_sub),
                            checked = state.options.medications,
                            onCheckedChange = { v ->
                                vm.updateOptions { it.copy(medications = v) }
                            },
                            showDivider = true,
                        )
                        IncludeRow(
                            icon = Icons.Filled.ShowChart,
                            title = stringResource(R.string.pdf_inc_hormones),
                            sub = stringResource(R.string.pdf_inc_hormones_sub),
                            checked = state.options.hormones,
                            onCheckedChange = { v ->
                                vm.updateOptions { it.copy(hormones = v) }
                            },
                            showDivider = true,
                        )
                        IncludeRow(
                            icon = Icons.Filled.EditNote,
                            title = stringResource(R.string.pdf_inc_journal),
                            sub = stringResource(R.string.pdf_inc_journal_sub),
                            checked = state.options.journal,
                            onCheckedChange = { v ->
                                vm.updateOptions { it.copy(journal = v) }
                            },
                            showDivider = true,
                        )
                        IncludeRow(
                            icon = Icons.Filled.Bloodtype,
                            title = stringResource(R.string.pdf_inc_labs),
                            sub = stringResource(R.string.pdf_inc_labs_sub),
                            checked = state.options.labReminders,
                            onCheckedChange = { v ->
                                vm.updateOptions { it.copy(labReminders = v) }
                            },
                            showDivider = false,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }

            item {
                val anySection = state.options.medications || state.options.hormones ||
                    state.options.journal || state.options.labReminders
                Button(
                    onClick = vm::generate,
                    enabled = !state.generating && anySection,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(50),
                ) {
                    if (state.generating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(stringResource(R.string.pdf_generating))
                    } else {
                        Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.pdf_generate))
                    }
                }
            }

            state.error?.let { msg ->
                item {
                    Text(
                        msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun IntroCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.tertiary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiary,
                )
            }
            Column(modifier = Modifier
                .padding(start = 14.dp)
                .weight(1f)) {
                Text(
                    stringResource(R.string.pdf_intro_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.pdf_intro_sub),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun IncludeRow(
    icon: ImageVector,
    title: String,
    sub: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDivider: Boolean,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier
                .padding(start = 14.dp)
                .weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}
