package com.douxev.eggshell.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.douxev.eggshell.R
import com.douxev.eggshell.data.FeaturesPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class FeaturesViewModel @Inject constructor(
    private val prefs: FeaturesPrefs,
) : ViewModel() {
    val medications: StateFlow<Boolean> = prefs.medications
    val journal: StateFlow<Boolean> = prefs.journal
    val hormones: StateFlow<Boolean> = prefs.hormones
    val weightTracking: StateFlow<Boolean> = prefs.weightTracking
    val photoTab: StateFlow<Boolean> = prefs.photoTab
    val voiceTab: StateFlow<Boolean> = prefs.voiceTab
    val bleeding: StateFlow<Boolean> = prefs.bleeding
    val appointments: StateFlow<Boolean> = prefs.appointments

    fun setMedications(v: Boolean) = prefs.setMedications(v)
    fun setJournal(v: Boolean) = prefs.setJournal(v)
    fun setHormones(v: Boolean) = prefs.setHormones(v)
    fun setWeightTracking(v: Boolean) = prefs.setWeightTracking(v)
    fun setPhotoTab(v: Boolean) = prefs.setPhotoTab(v)
    fun setVoiceTab(v: Boolean) = prefs.setVoiceTab(v)
    fun setBleeding(v: Boolean) = prefs.setBleeding(v)
    fun setAppointments(v: Boolean) = prefs.setAppointments(v)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeaturesScreen(
    onBack: () -> Unit,
    vm: FeaturesViewModel = hiltViewModel(),
) {
    val meds by vm.medications.collectAsState()
    val journal by vm.journal.collectAsState()
    val hormones by vm.hormones.collectAsState()
    val weight by vm.weightTracking.collectAsState()
    val photo by vm.photoTab.collectAsState()
    val voice by vm.voiceTab.collectAsState()
    val bleeding by vm.bleeding.collectAsState()
    val appointments by vm.appointments.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.features_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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
            item {
                Text(
                    stringResource(R.string.features_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        FeatureSwitchRow(
                            icon = Icons.Filled.LocalPharmacy,
                            title = stringResource(R.string.feature_medications_title),
                            sub = stringResource(R.string.feature_medications_sub),
                            checked = meds,
                            onCheckedChange = vm::setMedications,
                            showDivider = true,
                        )
                        FeatureSwitchRow(
                            icon = Icons.Filled.EditNote,
                            title = stringResource(R.string.feature_journal_title),
                            sub = stringResource(R.string.feature_journal_sub),
                            checked = journal,
                            onCheckedChange = vm::setJournal,
                            showDivider = true,
                        )
                        FeatureSwitchRow(
                            icon = Icons.Filled.Timeline,
                            title = stringResource(R.string.feature_hormones_title),
                            sub = stringResource(R.string.feature_hormones_sub),
                            checked = hormones,
                            onCheckedChange = vm::setHormones,
                            showDivider = true,
                        )
                        FeatureSwitchRow(
                            icon = Icons.Filled.MonitorWeight,
                            title = stringResource(R.string.feature_weight_title),
                            sub = stringResource(R.string.feature_weight_sub),
                            checked = weight,
                            onCheckedChange = vm::setWeightTracking,
                            showDivider = true,
                        )
                        FeatureSwitchRow(
                            icon = Icons.Filled.PhotoCamera,
                            title = stringResource(R.string.feature_photos_title),
                            sub = stringResource(R.string.feature_photos_sub),
                            checked = photo,
                            onCheckedChange = vm::setPhotoTab,
                            showDivider = true,
                        )
                        FeatureSwitchRow(
                            icon = Icons.Filled.GraphicEq,
                            title = stringResource(R.string.feature_voice_title),
                            sub = stringResource(R.string.feature_voice_sub),
                            checked = voice,
                            onCheckedChange = vm::setVoiceTab,
                            showDivider = true,
                        )
                        FeatureSwitchRow(
                            icon = Icons.Filled.Bloodtype,
                            title = stringResource(R.string.feature_bleeding_title),
                            sub = stringResource(R.string.feature_bleeding_sub),
                            checked = bleeding,
                            onCheckedChange = vm::setBleeding,
                            showDivider = true,
                        )
                        FeatureSwitchRow(
                            icon = Icons.Filled.Event,
                            title = stringResource(R.string.feature_appointments_title),
                            sub = stringResource(R.string.feature_appointments_sub),
                            checked = appointments,
                            onCheckedChange = vm::setAppointments,
                            showDivider = false,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun FeatureSwitchRow(
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
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f)
            ) {
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
