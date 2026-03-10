package net.shinytoaster.waxwatch.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import net.shinytoaster.waxwatch.R
import net.shinytoaster.waxwatch.WaxWatchConstants
import net.shinytoaster.waxwatch.data.DistanceUnit
import net.shinytoaster.waxwatch.data.SurfaceType
import net.shinytoaster.waxwatch.data.WaxRepository
import net.shinytoaster.waxwatch.data.WaxState
import net.shinytoaster.waxwatch.domain.WaxCalculator

class MainActivity : ComponentActivity() {

    private lateinit var repository: WaxRepository

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = WaxRepository(this)

        setContent {
            MaterialTheme {
                Scaffold(
                    topBar = { 
                        TopAppBar(
                            title = { 
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.foundation.Image(
                                        painter = painterResource(id = R.mipmap.ic_launcher),
                                        contentDescription = "WaxWatch App Icon",
                                        modifier = Modifier.size(44.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "WaxWatch",
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        fontSize = 22.sp
                                    )
                                }
                            }
                        ) 
                    }
                ) { padding ->
                    PermissionWrapper {
                        WaxWatchScreen(modifier = Modifier.padding(padding), repository = repository)
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionWrapper(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasNotificationPermission = isGranted
        }
    )

    LaunchedEffect(Unit) {
        if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    content()
}

@Composable
fun WaxWatchScreen(modifier: Modifier = Modifier, repository: WaxRepository) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var riderWeight by remember { mutableFloatStateOf(repository.getRiderWeight().toFloat()) }
    var waxType by remember { mutableStateOf(repository.getWaxType()) }
    var distanceUnit by remember { mutableStateOf(repository.getDistanceUnit()) }
    var alertThresholdPercent by remember { mutableIntStateOf(repository.getAlertThresholdPercent()) }
    var baseWaxLife by remember { mutableStateOf(repository.getBaseWaxLifeMeters()) }
    var profiles by remember { mutableStateOf(repository.getAllWaxStates().values.toList()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                profiles = repository.getAllWaxStates().values.toList()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun notifyExtension() {
        val intent = Intent(WaxWatchConstants.ACTION_STATE_UPDATED)
        context.sendBroadcast(intent)
    }

    LazyColumn(modifier = modifier.padding(16.dp).fillMaxSize()) {
        item {
            Text(stringResource(R.string.global_settings_title), style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(R.string.global_settings_desc),
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color.Gray
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            var riderWeightText by remember { mutableStateOf(riderWeight.toInt().toString()) }
            fun commitRiderWeight() {
                riderWeightText.toIntOrNull()?.let { weight ->
                    riderWeight = weight.toFloat()
                    repository.setRiderWeight(weight.toDouble())
                    val newLifeMeters = WaxCalculator.calculateMaxLifeMeters(weight.toDouble(), waxType)
                    baseWaxLife = newLifeMeters
                    repository.setBaseWaxLifeMeters(newLifeMeters)
                    notifyExtension()
                }
            }
            OutlinedTextField(
                value = riderWeightText,
                onValueChange = { riderWeightText = it },
                label = { Text(stringResource(R.string.label_rider_weight)) },
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commitRiderWeight() },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
            )

            Spacer(modifier = Modifier.height(8.dp))

            var waxExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { waxExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Wax Type: ${waxType.displayName}")
                }
                DropdownMenu(expanded = waxExpanded, onDismissRequest = { waxExpanded = false }) {
                    net.shinytoaster.waxwatch.data.WaxType.values().forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.displayName) },
                            onClick = {
                                waxType = type
                                repository.setWaxType(type)
                                val newLifeMeters = WaxCalculator.calculateMaxLifeMeters(riderWeight.toDouble(), type)
                                baseWaxLife = newLifeMeters
                                repository.setBaseWaxLifeMeters(newLifeMeters)
                                notifyExtension()
                                waxExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            var unitExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { unitExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Distance Unit: ${distanceUnit.name}")
                }
                DropdownMenu(expanded = unitExpanded, onDismissRequest = { unitExpanded = false }) {
                    DistanceUnit.values().forEach { unit ->
                        DropdownMenuItem(
                            text = { Text(unit.name) },
                            onClick = {
                                distanceUnit = unit
                                repository.setDistanceUnit(unit)
                                notifyExtension()
                                unitExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.label_rewax_alert, alertThresholdPercent), style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.Slider(
                value = alertThresholdPercent.toFloat(),
                onValueChange = { newVal -> 
                    val newInt = newVal.toInt()
                    alertThresholdPercent = newInt
                    repository.setAlertThresholdPercent(newInt)
                    notifyExtension()
                },
                valueRange = 0f..50f,
                steps = 49
            )
            Text(
                text = stringResource(R.string.rewax_alert_desc),
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color.Gray
            )
            Spacer(modifier = Modifier.height(16.dp))

            val resolvedUnitLabel = if (repository.resolveDistanceUnit() == DistanceUnit.MILES) "Miles" else "KM"
            var waxLifeText by remember(baseWaxLife, distanceUnit) {
                val resolvedUnit = repository.resolveDistanceUnit()
                val displayVal = if (resolvedUnit == DistanceUnit.MILES) {
                    WaxCalculator.metersToMiles(baseWaxLife)
                } else {
                    WaxCalculator.metersToKm(baseWaxLife)
                }
                mutableStateOf(displayVal.toInt().toString())
            }

            fun commitBaseWaxLife() {
                waxLifeText.toDoubleOrNull()?.let { dist ->
                    val resolvedUnit = repository.resolveDistanceUnit()
                    val meters = if (resolvedUnit == DistanceUnit.MILES) {
                        WaxCalculator.milesToMeters(dist)
                    } else {
                        WaxCalculator.kmToMeters(dist)
                    }
                    baseWaxLife = meters
                    repository.setBaseWaxLifeMeters(meters)
                    notifyExtension()
                }
            }
            OutlinedTextField(
                value = waxLifeText,
                onValueChange = { waxLifeText = it },
                label = { Text("Calculated Base Wax Life ($resolvedUnitLabel)") },
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commitBaseWaxLife() },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
            )
            Text(
                text = "This base wax life is automatically calculated based on the Wax Type and Rider Weight above, but you can manually override it here if you prefer a custom baseline distance.",
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color.Gray,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))
            Text("Activity Profiles", style = MaterialTheme.typography.titleLarge)
            
            if (profiles.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "No profiles discovered yet.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "To start tracking, simply begin a ride on your Karoo using any of your Activity Profiles. WaxWatch will automatically detect the profile and add it here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Text(
                    text = "The app automatically discovers your Karoo Activity Profiles as you start rides. Assign the correct Surface Type below to adjust your wear multipliers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = androidx.compose.ui.graphics.Color.Gray,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }

        items(profiles) { state ->
            val resolvedUnit = repository.resolveDistanceUnit()
            ProfileCard(
                state = state,
                resolvedUnit = resolvedUnit,
                alertThresholdPercent = alertThresholdPercent,
                onSurfaceTypeChanged = { newSurface ->
                    val newState = state.copy(surfaceType = newSurface)
                    repository.saveWaxState(newState)
                    profiles = repository.getAllWaxStates().values.toList()
                    notifyExtension()
                },
                onRainSpash = {
                    val penalty = state.maxLifeMeters * 0.30
                    val newState = state.copy(remainingDistanceMeters = (state.remainingDistanceMeters - penalty).coerceAtLeast(0.0))
                    repository.saveWaxState(newState)
                    profiles = repository.getAllWaxStates().values.toList()
                    notifyExtension()
                },
                onRewax = {
                    val newState = state.copy(remainingDistanceMeters = baseWaxLife, maxLifeMeters = baseWaxLife, alertTriggered = false)
                    repository.saveWaxState(newState)
                    profiles = repository.getAllWaxStates().values.toList()
                    notifyExtension()
                },
                onRemainingDistanceChanged = { newDistMeters ->
                    val cappedDist = minOf(newDistMeters, state.maxLifeMeters)
                    val newState = state.copy(remainingDistanceMeters = maxOf(0.0, cappedDist))
                    repository.saveWaxState(newState)
                    profiles = repository.getAllWaxStates().values.toList()
                    notifyExtension()
                },
                onMaxLifeChanged = { newMaxMeters ->
                    val cappedMax = maxOf(0.0, newMaxMeters)
                    val cappedRemaining = minOf(state.remainingDistanceMeters, cappedMax)
                    val newState = state.copy(maxLifeMeters = cappedMax, remainingDistanceMeters = maxOf(0.0, cappedRemaining))
                    repository.saveWaxState(newState)
                    profiles = repository.getAllWaxStates().values.toList()
                    notifyExtension()
                },
                onDelete = {
                    repository.deleteWaxState(state.profileId)
                    profiles = repository.getAllWaxStates().values.toList()
                    notifyExtension()
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun ProfileCard(
    state: WaxState,
    resolvedUnit: DistanceUnit,
    alertThresholdPercent: Int,
    onSurfaceTypeChanged: (SurfaceType) -> Unit,
    onRainSpash: () -> Unit,
    onRewax: () -> Unit,
    onRemainingDistanceChanged: (Double) -> Unit,
    onMaxLifeChanged: (Double) -> Unit,
    onDelete: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var showRewaxConfirm by remember { mutableStateOf(false) }
    var showRainConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.profile_title_format, state.profileId),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Profile",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            
            val isMiles = resolvedUnit == DistanceUnit.MILES
            val unitLabel = if (isMiles) "mi" else "km"
            
            // Remember keys use only profileId + unit so external value changes don't
            // reinitialise the text while the user is actively editing.
            var remainingText by remember(state.profileId, resolvedUnit) {
                val rem = if (isMiles) WaxCalculator.metersToMiles(state.remainingDistanceMeters)
                           else WaxCalculator.metersToKm(state.remainingDistanceMeters)
                mutableStateOf(rem.toInt().toString())
            }
            var maxLifeText by remember(state.profileId, resolvedUnit) {
                val maxLife = if (isMiles) WaxCalculator.metersToMiles(state.maxLifeMeters)
                              else WaxCalculator.metersToKm(state.maxLifeMeters)
                mutableStateOf(maxLife.toInt().toString())
            }

            fun commitMaxLife() {
                maxLifeText.toDoubleOrNull()?.let { distValue ->
                    val distMeters = if (isMiles) WaxCalculator.milesToMeters(distValue)
                                     else WaxCalculator.kmToMeters(distValue)
                    onMaxLifeChanged(distMeters)
                }
            }
            fun commitRemaining() {
                remainingText.toDoubleOrNull()?.let { distValue ->
                    val distMeters = if (isMiles) WaxCalculator.milesToMeters(distValue)
                                     else WaxCalculator.kmToMeters(distValue)
                    onRemainingDistanceChanged(distMeters)
                }
            }

            val currentPercent = state.remainingPercentage
            val isAlert = currentPercent < alertThresholdPercent
            val textColor = if (isAlert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = maxLifeText,
                    onValueChange = { maxLifeText = it },
                    label = { Text("Max ($unitLabel)") },
                    modifier = Modifier.weight(1f).onFocusChanged { if (!it.isFocused) commitMaxLife() },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { commitMaxLife() })
                )
                OutlinedTextField(
                    value = remainingText,
                    onValueChange = { remainingText = it },
                    label = { Text(stringResource(R.string.label_remaining, unitLabel)) },
                    modifier = Modifier.weight(1f).onFocusChanged { if (!it.isFocused) commitRemaining() },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    textStyle = androidx.compose.ui.text.TextStyle(color = textColor)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            SurfaceTypeSelector(selectedType = state.surfaceType, onSelection = onSurfaceTypeChanged)
            
            Spacer(modifier = Modifier.height(8.dp))
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { showRewaxConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.button_rewax))
                }
                Button(
                    onClick = { showRainConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.button_rain))
                }
            }
            Text(
                text = "Riding in wet conditions aggressively strips wax and invites rust. Tap the Rain button after a wet ride to immediately deduct 30% of this chain's maximum wax life from its remaining lifespan.",
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color.Gray,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }

    if (showRewaxConfirm) {
        AlertDialog(
            onDismissRequest = { showRewaxConfirm = false },
            title = { Text("Confirm Rewax") },
            text = { Text("Are you sure you want to reset this chain's life to 100%?") },
            confirmButton = {
                TextButton(onClick = {
                    onRewax()
                    showRewaxConfirm = false
                }) {
                    Text("Yes, Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRewaxConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showRainConfirm) {
        AlertDialog(
            onDismissRequest = { showRainConfirm = false },
            title = { Text("Confirm Rain / Wet Ride") },
            text = { Text("Are you sure you want to deduct 30% of this chain's maximum wax life from its remaining lifespan?") },
            confirmButton = {
                TextButton(onClick = {
                    onRainSpash()
                    showRainConfirm = false
                }) {
                    Text("Yes, Reduce")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRainConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Profile Tracking?") },
            text = { Text("Are you sure you want to stop tracking wax for the profile \"${state.profileId}\"? This will delete all its history.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SurfaceTypeSelector(selectedType: SurfaceType, onSelection: (SurfaceType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("Surface: ${selectedType.displayName}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SurfaceType.values().forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.displayName) },
                    onClick = {
                        onSelection(type)
                        expanded = false
                    }
                )
            }
        }
    }
}
