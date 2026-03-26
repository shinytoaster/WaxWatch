package net.shinytoaster.waxwatch.extension

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
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

    private var activeProfileName: String? = null
    private var lastDistanceMeters: Double = 0.0
    private var lastSaveTime: Long = 0L

    private val waxState = MutableStateFlow<Pair<Double, Double>?>(null)

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
                waxState.value = Pair(remainingMeters, pct)
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

        @Suppress("UNUSED_VARIABLE")
        val initTrigger = types.size

        karooSystem.connect { connected ->
            if (connected) {
                // Ensure we only have one distance collector running
                distanceCollectionJob?.cancel()
                distanceCollectionJob = serviceScope.launch(Dispatchers.IO) {
                    try {
                        karooSystem.streamDataFlow(io.hammerhead.karooext.models.DataType.Type.DISTANCE)
                            .collect { state ->
                                if (state is StreamState.Streaming) {
                                    state.dataPoint.singleValue?.let { updateDistance(it) }
                                }
                            }
                    } catch (e: Exception) {
                        Log.e("WaxWatch", "Distance stream error", e)
                    }
                }

                karooSystem.addConsumer { event: io.hammerhead.karooext.models.ActiveRideProfile ->
                    if (activeProfileName != event.profile.name) {
                        flushStateToDisk()
                        activeProfileName = event.profile.name
                        waxState.value = null
                        refreshActiveProfileData()
                    }
                }

                karooSystem.addConsumer { rideState: RideState ->
                    if (rideState is RideState.Idle) {
                        flushStateToDisk()
                        activeProfileName = null
                        lastDistanceMeters = 0.0
                    }
                }
            }
        }
    }

    private fun refreshActiveProfileData() {
        val profileName = activeProfileName ?: repository.getSavedProfileIds().firstOrNull() ?: return
        val state = repository.getWaxState(profileName) ?: return
        
        if (activeProfileName != null && lastDistanceMeters > 0) {
            val consumed = lastDistanceMeters * state.surfaceType.multiplier
            state.remainingDistanceMeters = maxOf(0.0, state.remainingDistanceMeters - consumed)
        }

        waxState.value = Pair(state.remainingDistanceMeters, state.remainingPercentage)
    }

    private fun updateDistance(newTotalDistance: Double) {
        if (lastDistanceMeters == 0.0) {
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
        waxState.value = Pair(newRemaining, pct)

        if (!state.alertTriggered) {
            val threshold = repository.getAlertThresholdPercent()
            if (pct < threshold) {
                sendAlertNotification(currentProfileId)
                state.alertTriggered = true
            }
        }

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

    private fun sendAlertNotification(profileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, "waxwatch_alerts")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("WaxWatch Alert")
            .setContentText("Your chain on $profileName is below your wax threshold. Time to rewax!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(profileName.hashCode(), notification)
    }

    private fun createNotificationChannel() {
        val name = "WaxWatch Alerts"
        val channel = NotificationChannel("waxwatch_alerts", name, NotificationManager.IMPORTANCE_HIGH)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
