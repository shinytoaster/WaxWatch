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
import kotlinx.coroutines.flow.MutableStateFlow
import net.shinytoaster.waxwatch.WaxWatchConstants
import net.shinytoaster.waxwatch.data.WaxRepository

/**
 * WaxWatch Karoo Extension — background service that feeds data to the Karoo OS data fields.
 *
 * ## Architecture
 *
 * State is held in [waxState], a [MutableStateFlow] of (remainingMeters, percentage).
 * Both [WaxLifeDataTypeImpl] and [WaxDistDataTypeImpl] collect this flow inside coroutines
 * launched in their `startStream` override. Updating [waxState] from any source (app edits,
 * ride distance, profile change) automatically pushes a fresh emission to every active Karoo
 * data field — no manual emitter list management needed.
 *
 * ## Cross-process updates
 *
 * This service runs in a separate process from [net.shinytoaster.waxwatch.ui.MainActivity].
 * When the user edits profile values, MainActivity sends a `startService` intent with the
 * new remaining/max values as extras, which are handled in [onStartCommand] and immediately
 * applied to [waxState].
 *
 * ## ID restriction
 *
 * The Karoo Extension ID (first constructor parameter) must **not** contain periods (`.`).
 * Using the package name will cause a crash on connection.
 */
class WaxWatchExtension : KarooExtension("waxwatch", "1.0") {

    private val repository by lazy { WaxRepository(applicationContext) }
    private lateinit var karooSystem: KarooSystemService

    private var activeProfileName: String? = null
    private var lastDistanceMeters: Double = 0.0

    /**
     * Single source of truth: Pair(remainingMeters, percentage 0–100).
     * Collected by both data type implementations inside their startStream coroutines.
     */
    private val waxState = MutableStateFlow<Pair<Double, Double>?>(null)

    private val waxLifeType by lazy { WaxLifeDataTypeImpl(waxState) }
    private val waxDistType by lazy { WaxDistDataTypeImpl(repository, waxState) }

    override val types: List<DataTypeImpl> by lazy {
        listOf(waxLifeType, waxDistType)
    }

    /**
     * Receives profile state updates from MainActivity via `startService` intent.
     * Applies them directly to [waxState], bypassing the stale cross-process SharedPreferences cache.
     */
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

        // Force lazy init so Karoo OS can discover the data types immediately on service start
        @Suppress("UNUSED_VARIABLE")
        val initTrigger = types.size

        karooSystem.connect { connected ->
            if (connected) {
                karooSystem.addConsumer(
                    OnStreamState.StartStreaming(io.hammerhead.karooext.models.DataType.Type.DISTANCE)
                ) { event: OnStreamState ->
                    if (event.state is StreamState.Streaming) {
                        val stream = event.state as StreamState.Streaming
                        if (stream.dataPoint.dataTypeId == io.hammerhead.karooext.models.DataType.Type.DISTANCE) {
                            stream.dataPoint.values[io.hammerhead.karooext.models.DataType.Field.SINGLE]
                                ?.let { updateDistance(it) }
                        }
                    }
                }

                karooSystem.addConsumer { event: io.hammerhead.karooext.models.ActiveRideProfile ->
                    Log.d("WaxWatch", "ActiveRideProfile: ${event.profile.name}")
                    if (activeProfileName != event.profile.name) {
                        activeProfileName = event.profile.name
                        // Clear state so the fresh disk read isn't mixed with stale cached values
                        waxState.value = null
                        refreshActiveProfileData()
                    }
                }

                karooSystem.addConsumer { rideState: RideState ->
                    if (rideState is RideState.Idle) {
                        activeProfileName = null
                        lastDistanceMeters = 0.0
                    }
                }
            }
        }
    }

    private fun refreshActiveProfileData() {
        val profileName = activeProfileName ?: repository.getSavedProfileIds().firstOrNull() ?: return
        val state = repository.getWaxState(profileName) ?: run {
            val newMax = repository.getBaseWaxLifeMeters()
            val newState = net.shinytoaster.waxwatch.data.WaxState(profileName, newMax, newMax)
            repository.saveWaxState(newState)
            newState
        }
        waxState.value = Pair(state.remainingDistanceMeters, state.remainingPercentage)
    }

    private fun updateDistance(newTotalDistance: Double) {
        val currentProfileId = activeProfileName ?: return
        if (lastDistanceMeters == 0.0) {
            lastDistanceMeters = newTotalDistance
            return
        }
        val delta = newTotalDistance - lastDistanceMeters
        if (delta > 0) {
            val state = repository.getWaxState(currentProfileId) ?: return
            val consumed = delta * state.surfaceType.multiplier
            // Use in-memory waxState as the authoritative baseline to avoid stale SharedPreferences reads
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
            repository.saveWaxState(state)
        }
        lastDistanceMeters = newTotalDistance
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
}
