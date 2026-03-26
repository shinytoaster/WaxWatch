package net.shinytoaster.waxwatch.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import net.shinytoaster.waxwatch.extension.WaxWatchExtension

class MainActivity : ComponentActivity() {

    private lateinit var repository: WaxRepository

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = WaxRepository(this)

        setContent {
            var currentProfileId by remember { mutableStateOf<String?>(null) }
            var profilesMap by remember { mutableStateOf(repository.getAllWaxStates()) }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        profilesMap = repository.getAllWaxStates()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            MaterialTheme {
                Scaffold(
                    topBar = { 
                        TopAppBar(
                            title = { 
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (currentProfileId == null) {
                                        androidx.compose.foundation.Image(
                                            painter = painterResource(id = R.mipmap.ic_launcher),
                                            contentDescription = "WaxWatch App Icon",
                                            modifier = Modifier.size(44.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = "WaxWatch",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 22.sp
                                        )
                                    } else {
                                        Text(
                                            text = currentProfileId!!,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 20.sp
                                        )
                                    }
                                }
                            },
                            navigationIcon = {
                                if (currentProfileId != null) {
                                    IconButton(onClick = { currentProfileId = null }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                }
                            }
                        ) 
                    }
                ) { padding ->
                    PermissionWrapper {
                        Box(modifier = Modifier.padding(padding)) {
                            if (currentProfileId == null) {
                                WaxWatchScreen(
                                    repository = repository,
                                    profiles = profilesMap.values.toList(),
                                    onProfileClick = { currentProfileId = it }
                                )
                            } else {
                                val state = profilesMap[currentProfileId]
                                if (state != null) {
                                    ProfileDetailScreen(
                                        state = state,
                                        repository = repository,
                                        onBack = { 
                                            profilesMap = repository.getAllWaxStates()
                                            currentProfileId = null 
                                        },
                                        onDeleted = { 
                                            profilesMap = repository.getAllWaxStates()
                                            currentProfileId = null 
                                        },
                                        onStateChanged = { 
                                            profilesMap = repository.getAllWaxStates()
                                        }
                                    )
                                } else {
                                    currentProfileId = null
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionWrapper(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var areNotificationsEnabled by remember {
        mutableStateOf(checkNotificationsEnabled(context))
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            areNotificationsEnabled = isGranted
        }
    )

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                areNotificationsEnabled = checkNotificationsEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        if (!areNotificationsEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    content()
}

private fun checkNotificationsEnabled(context: Context): Boolean {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (!notificationManager.areNotificationsEnabled()) return false
    
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

@Composable
fun WaxWatchScreen(
    repository: WaxRepository,
    profiles: List<WaxState>,
    onProfileClick: (String) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var riderWeight by remember { mutableFloatStateOf(repository.getRiderWeight().toFloat()) }
    var waxType by remember { mutableStateOf(repository.getWaxType()) }
    var distanceUnit by remember { mutableStateOf(repository.getDistanceUnit()) }
    var alertThresholdPercent by remember { mutableIntStateOf(repository.getAlertThresholdPercent()) }
    var baseWaxLife by remember { mutableStateOf(repository.getBaseWaxLifeMeters()) }
    var notificationsEnabled by remember { mutableStateOf(checkNotificationsEnabled(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = checkNotificationsEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            notificationsEnabled = isGranted
        }
    )

    fun notifyExtension() {
        val intent = Intent(context, WaxWatchExtension::class.java)
        intent.action = WaxWatchConstants.ACTION_STATE_UPDATED
        context.startService(intent)
    }

    LazyColumn(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        item {
            Text(stringResource(R.string.global_settings_title), style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(R.string.global_settings_desc),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (!notificationsEnabled) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Rewax alerts are disabled",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "Enable notifications to receive alerts when your wax is low.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        TextButton(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            }
                        }) {
                            Text("Fix")
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Notifications, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Rewax alerts are active",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
            
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
                modifier = Modifier.fillMaxWidth().onFocusChanged { state -> if (!state.isFocused) commitRiderWeight() },
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
                    net.shinytoaster.waxwatch.data.WaxType.entries.forEach { type ->
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
                    DistanceUnit.entries.forEach { unit ->
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
                color = Color.Gray
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
                modifier = Modifier.fillMaxWidth().onFocusChanged { state -> if (!state.isFocused) commitBaseWaxLife() },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
            )
            Text(
                text = "This base wax life is automatically calculated based on the settings above, but you can manually override it here.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
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
                    text = "Profiles are discovered as you start rides. Tap a profile below to manage its specific wax state.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }

        items(profiles, key = { it.profileId }) { state ->
            val resolvedUnit = repository.resolveDistanceUnit()
            val unitLabel = if (resolvedUnit == DistanceUnit.MILES) "mi" else "km"
            val displayRem = if (resolvedUnit == DistanceUnit.MILES) 
                WaxCalculator.metersToMiles(state.remainingDistanceMeters)
            else 
                WaxCalculator.metersToKm(state.remainingDistanceMeters)

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onProfileClick(state.profileId) }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(state.profileId, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${displayRem.toInt()} $unitLabel remaining (${state.remainingPercentage.toInt()}%)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.remainingPercentage < alertThresholdPercent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Edit")
                }
            }
        }
    }
}

@Composable
fun ProfileDetailScreen(
    state: WaxState,
    repository: WaxRepository,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onStateChanged: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val resolvedUnit = repository.resolveDistanceUnit()
    val alertThresholdPercent = repository.getAlertThresholdPercent()
    val baseWaxLife = repository.getBaseWaxLifeMeters()

    BackHandler(onBack = onBack)

    fun notifyExtension(updatedState: WaxState) {
        val intent = Intent(context, WaxWatchExtension::class.java)
        intent.action = WaxWatchConstants.ACTION_STATE_UPDATED
        intent.putExtra(WaxWatchConstants.EXTRA_PROFILE_ID, updatedState.profileId)
        intent.putExtra(WaxWatchConstants.EXTRA_REMAINING_METERS, updatedState.remainingDistanceMeters)
        intent.putExtra(WaxWatchConstants.EXTRA_MAX_LIFE_METERS, updatedState.maxLifeMeters)
        context.startService(intent)
    }

    LazyColumn(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        item {
            var showDeleteConfirm by remember { mutableStateOf(false) }
            var showRewaxConfirm by remember { mutableStateOf(false) }
            var showRainConfirm by remember { mutableStateOf(false) }

            val isMiles = resolvedUnit == DistanceUnit.MILES
            val unitLabel = if (isMiles) "mi" else "km"

            var remainingText by remember(state.profileId, resolvedUnit, state.remainingDistanceMeters) {
                val rem = if (isMiles) WaxCalculator.metersToMiles(state.remainingDistanceMeters)
                           else WaxCalculator.metersToKm(state.remainingDistanceMeters)
                mutableStateOf(rem.toInt().toString())
            }
            var maxLifeText by remember(state.profileId, resolvedUnit, state.maxLifeMeters) {
                val maxLife = if (isMiles) WaxCalculator.metersToMiles(state.maxLifeMeters)
                              else WaxCalculator.metersToKm(state.maxLifeMeters)
                mutableStateOf(maxLife.toInt().toString())
            }

            fun commitMaxLife() {
                maxLifeText.toDoubleOrNull()?.let { distValue ->
                    val distMeters = if (isMiles) WaxCalculator.milesToMeters(distValue)
                                     else WaxCalculator.kmToMeters(distValue)
                    if (distMeters != state.maxLifeMeters) {
                        val cappedMax = maxOf(0.0, distMeters)
                        val cappedRemaining = minOf(state.remainingDistanceMeters, cappedMax)
                        val newState = state.copy(maxLifeMeters = cappedMax, remainingDistanceMeters = maxOf(0.0, cappedRemaining))
                        repository.saveWaxState(newState)
                        notifyExtension(newState)
                        onStateChanged()
                    }
                }
            }
            fun commitRemaining() {
                remainingText.toDoubleOrNull()?.let { distValue ->
                    val distMeters = if (isMiles) WaxCalculator.milesToMeters(distValue)
                                     else WaxCalculator.kmToMeters(distValue)
                    if (distMeters != state.remainingDistanceMeters) {
                        val cappedDist = minOf(distMeters, state.maxLifeMeters)
                        val newState = state.copy(remainingDistanceMeters = maxOf(0.0, cappedDist))
                        repository.saveWaxState(newState)
                        notifyExtension(newState)
                        onStateChanged()
                    }
                }
            }

            val currentPercent = state.remainingPercentage
            val isAlert = currentPercent < alertThresholdPercent
            val textColor = if (isAlert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = maxLifeText,
                    onValueChange = { maxLifeText = it },
                    label = { Text("Maximum Wax Life ($unitLabel)") },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { state -> if (!state.isFocused) commitMaxLife() },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { commitMaxLife(); focusManager.moveFocus(FocusDirection.Down) })
                )
                OutlinedTextField(
                    value = remainingText,
                    onValueChange = { remainingText = it },
                    label = { Text("Remaining Wax Life ($unitLabel)") },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { state -> if (!state.isFocused) commitRemaining() },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    textStyle = TextStyle(color = textColor)
                )

                SurfaceTypeSelector(
                    selectedType = state.surfaceType, 
                    onSelection = { newSurface ->
                        val newState = state.copy(surfaceType = newSurface)
                        repository.saveWaxState(newState)
                        notifyExtension(newState)
                        onStateChanged()
                    }
                )

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

                Text(
                    text = "Tap the Rain button after a wet ride to immediately deduct 30% of this chain's maximum wax life.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                Divider()

                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Delete Profile Tracking")
                }
            }

            if (showRewaxConfirm) {
                AlertDialog(
                    onDismissRequest = { showRewaxConfirm = false },
                    title = { Text("Confirm Rewax") },
                    text = { Text("Are you sure you want to reset this chain's life to 100%?") },
                    confirmButton = {
                        TextButton(onClick = {
                            val newState = state.copy(remainingDistanceMeters = baseWaxLife, maxLifeMeters = baseWaxLife, alertTriggered = false)
                            repository.saveWaxState(newState)
                            notifyExtension(newState)
                            onStateChanged()
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
                            val penalty = state.maxLifeMeters * 0.30
                            val newRemaining = (state.remainingDistanceMeters - penalty).coerceAtLeast(0.0)
                            val newState = state.copy(remainingDistanceMeters = newRemaining)
                            repository.saveWaxState(newState)
                            notifyExtension(newState)
                            onStateChanged()
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
                    text = { Text("Are you sure you want to stop tracking wax for the profile \"${state.profileId}\"?") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                repository.deleteWaxState(state.profileId)
                                onDeleted()
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
    }
}

@Composable
fun SurfaceTypeSelector(selectedType: SurfaceType, onSelection: (SurfaceType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Surface: ${selectedType.displayName}")
        }
        DropdownMenu(
            expanded = expanded, 
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            SurfaceType.entries.forEach { type ->
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
