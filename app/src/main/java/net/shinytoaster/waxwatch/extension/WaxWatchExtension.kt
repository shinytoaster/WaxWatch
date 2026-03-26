package net.shinytoaster.waxwatch.extension

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import net.shinytoaster.waxwatch.R
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import net.shinytoaster.waxwatch.WaxWatchConstants
import net.shinytoaster.waxwatch.data.WaxRepository

/**
 * WaxWatch Karoo Extension — background service that feeds data to the Karoo OS data fields.
 */
class WaxWatchExtension : KarooExtension("waxwatch", "1.0") {

    private val repository by lazy { WaxRepository(applicationContext) }
    private lateinit var karooSystem: KarooSystemService
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var distanceCollectionJob: Job? = null

    private val NOTIFICATION_ID = 1001

    private var activeProfileName: String? = null
    private var lastDistanceMeters: Double = -1.0
    private var lastSaveTime: Long = 0L

    // Track consumer IDs so we can cleanly remove them before re-registering on reconnect
    private var rideStateConsumerId: String? = null
    private var activeProfileConsumerId: String? = null

    private val waxState = MutableStateFlow<Triple<Double, Double, Boolean>?>(null)

    private val waxLifeType by lazy { WaxLifeDataTypeImpl(waxState) }
    private val waxDistType by lazy { WaxDistDataTypeImpl(repository, waxState) }

    override val types: List<DataTypeImpl> by lazy {
        listOf(waxLifeType, waxDistType)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == WaxWatchConstants.ACTION_STATE_UPDATED) {
            val profileId = intent.getStringExtra(WaxWatchConstants.EXTRA_PROFILE_ID)
            val remainingMeters = intent.getDoubleExtra(WaxWatchConstants.EXTRA_REMAINING_METERS, -1.0)
            val maxLifeMeters = intent.getDoubleExtra(WaxWatchConstants.EXTRA_MAX_LIFE_METERS, -1.0)

            if (profileId != null && remainingMeters >= 0.0 && maxLifeMeters > 0.0) {
                val pct = (remainingMeters / maxLifeMeters * 100.0).coerceIn(0.0, 100.0)
                val alertTriggered = repository.getWaxState(profileId)?.alertTriggered ?: false
                waxState.value = Triple(remainingMeters, pct, alertTriggered)
            } else {
                refreshActiveProfileData()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        createNotificationChannel()

        val foregroundNotification = NotificationCompat.Builder(this, "waxwatch_service")
            .setSmallIcon(R.drawable.ic_chain_warning)
            .setContentTitle("WaxWatch Tracking")
            .setContentText("Monitoring chain wax life...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                foregroundNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, foregroundNotification)
        }

        @Suppress("UNUSED_VARIABLE")
        val initTrigger = types.size

        karooSystem.connect { connected ->
            if (connected) {
                // Remove old consumers before re-registering to prevent duplicates on reconnect
                rideStateConsumerId?.let { karooSystem.removeConsumer(it) }
                activeProfileConsumerId?.let { karooSystem.removeConsumer(it) }

                // Register ActiveRideProfile FIRST before starting the distance stream.
                // addConsumer immediately delivers the current state, so by the time the
                // distance coroutine starts emitting, activeProfileName will already be set.
                activeProfileConsumerId = karooSystem.addConsumer { event: ActiveRideProfile ->
                    if (activeProfileName != event.profile.name) {
                        flushStateToDisk()
                        activeProfileName = event.profile.name
                        waxState.value = null
                        refreshActiveProfileData()
                    }
                }

                rideStateConsumerId = karooSystem.addConsumer { rideState: RideState ->
                    when (rideState) {
                        is RideState.Idle -> {
                            flushStateToDisk()
                            activeProfileName = null
                            lastDistanceMeters = -1.0
                        }
                        is RideState.Paused -> {
                            // Reset baseline so on resume the first delta is correct,
                            // not a large jump covering the paused period
                            lastDistanceMeters = -1.0
                        }
                        else -> { /* Recording – no action needed */ }
                    }
                }

                // Start distance stream AFTER consumers are registered
                distanceCollectionJob?.cancel()
                distanceCollectionJob = serviceScope.launch(Dispatchers.IO) {
                    karooSystem.streamDataFlow(io.hammerhead.karooext.models.DataType.Type.DISTANCE)
                        .collect { state ->
                            try {
                                if (state is StreamState.Streaming) {
                                    state.dataPoint.singleValue?.let {
                                        updateDistance(it)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("WaxWatch", "Distance stream error caught inside loop", e)
                            }
                        }
                }
            }
        }
    }

    private fun refreshActiveProfileData() {
        val profileName = activeProfileName ?: repository.getSavedProfileIds().firstOrNull() ?: return
        val state = repository.getWaxState(profileName) ?: run {
            val newMax = repository.getBaseWaxLifeMeters()
            val newState = net.shinytoaster.waxwatch.data.WaxState(
                profileId = profileName,
                maxLifeMeters = newMax,
                remainingDistanceMeters = newMax
            )
            repository.saveWaxState(newState)
            newState
        }
        
        if (activeProfileName != null && lastDistanceMeters >= 0.0) {
            val consumed = lastDistanceMeters * state.surfaceType.multiplier
            state.remainingDistanceMeters = maxOf(0.0, state.remainingDistanceMeters - consumed)
        }

        waxState.value = Triple(state.remainingDistanceMeters, state.remainingPercentage, state.alertTriggered)
    }

    private fun updateDistance(newTotalDistance: Double) {
        if (lastDistanceMeters < 0.0) {
            lastDistanceMeters = newTotalDistance
            return
        }
        val delta = newTotalDistance - lastDistanceMeters
        if (delta <= 0) {
            lastDistanceMeters = newTotalDistance
            return
        }

        val currentProfileId = activeProfileName ?: run {
            lastDistanceMeters = newTotalDistance
            return
        }

        val state = repository.getWaxState(currentProfileId) ?: return
        val consumed = delta * state.surfaceType.multiplier
        
        val baseRemaining = waxState.value?.first ?: state.remainingDistanceMeters
        val newRemaining = maxOf(0.0, baseRemaining - consumed)
        
        state.remainingDistanceMeters = newRemaining
        val pct = state.remainingPercentage

        if (!state.alertTriggered) {
            val threshold = repository.getAlertThresholdPercent()
            if (pct <= threshold) {
                sendAlertNotification(currentProfileId, newRemaining)
                state.alertTriggered = true
            }
        }

        waxState.update { Triple(newRemaining, pct, state.alertTriggered) }

        val now = System.currentTimeMillis()
        if (now - lastSaveTime > 10000) {
            repository.saveWaxState(state)
            lastSaveTime = now
        }
        
        lastDistanceMeters = newTotalDistance
    }

    private fun flushStateToDisk() {
        val currentProfileId = activeProfileName ?: return
        val currentState = waxState.value ?: return
        val state = repository.getWaxState(currentProfileId) ?: return
        
        state.remainingDistanceMeters = currentState.first
        repository.saveWaxState(state)
        lastSaveTime = System.currentTimeMillis()
    }

    private fun sendAlertNotification(profileName: String, remainingMeters: Double) {
        val unit = repository.resolveDistanceUnit()
        val displayDist = if (unit == net.shinytoaster.waxwatch.data.DistanceUnit.MILES) {
            String.format("%.1f mi", remainingMeters / 1609.34)
        } else {
            String.format("%.1f km", remainingMeters / 1000.0)
        }

        serviceScope.launch(Dispatchers.Main) {
            // 1. Visual Alert
            val alert = InRideAlert(
                id = "wax_critical",
                title = "Wax Warning",
                detail = "Chain life is below configured threshold. $displayDist left. Surface: $profileName.",
                icon = R.drawable.ic_chain_warning,
                backgroundColor = R.color.karoo_red,
                textColor = R.color.white,
                autoDismissMs = 0L // Persistent until dismissed by user
            )
            karooSystem.dispatch(alert)

            // 2. Audio Alert (Critical Tone mimic)
            val beep = PlayBeepPattern(listOf(
                PlayBeepPattern.Tone(4000, 200),
                PlayBeepPattern.Tone(0, 100),
                PlayBeepPattern.Tone(4000, 500)
            ))
            karooSystem.dispatch(beep)
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Service Channel (Min Importance) - purely to satisfy Foreground Service requirements
        val serviceName = "WaxWatch Service"
        val serviceChannel = NotificationChannel("waxwatch_service", serviceName, NotificationManager.IMPORTANCE_MIN)
        serviceChannel.setShowBadge(false)
        nm.createNotificationChannel(serviceChannel)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
