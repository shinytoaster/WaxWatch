package net.shinytoaster.waxwatch.extension

import android.Manifest
import android.content.Context
import android.app.NotificationManager
import android.app.NotificationChannel
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import net.shinytoaster.waxwatch.WaxWatchConstants
import net.shinytoaster.waxwatch.data.WaxRepository

class WaxWatchExtension : KarooExtension("waxwatch", "1.0") {

    private lateinit var repository: WaxRepository
    private lateinit var karooSystem: KarooSystemService

    private var activeProfileName: String? = null
    private var lastDistanceMeters: Double = 0.0

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WaxWatchConstants.ACTION_STATE_UPDATED) {
                Log.d("WaxWatch", "Received ACTION_STATE_UPDATED broadcast")
                refreshActiveProfileData()
            }
        }
    }

    private val waxLifeDataType by lazy {
        WaxLifeDataTypeImpl {
            val currentProfileId = activeProfileName ?: repository.getSavedProfileIds().firstOrNull()
            if (currentProfileId == null) {
                Log.d("WaxWatch", "getCurrentPercentage: No profile active or saved, returning null")
                return@WaxLifeDataTypeImpl null
            }
            val state = repository.getWaxState(currentProfileId)
            if (state == null) {
                Log.d("WaxWatch", "getCurrentPercentage: State for $currentProfileId is null, returning null")
                return@WaxLifeDataTypeImpl null
            }
            val pct = state.remainingPercentage
            Log.d("WaxWatch", "getCurrentPercentage: profile=$currentProfileId, pct=$pct")
            pct
        }
    }

    private val waxDistDataType by lazy {
        WaxDistDataTypeImpl(repository) {
            val currentProfileId = activeProfileName ?: repository.getSavedProfileIds().firstOrNull()
            if (currentProfileId == null) {
                return@WaxDistDataTypeImpl null
            }
            val state = repository.getWaxState(currentProfileId)
            state?.remainingDistanceMeters
        }
    }

    override val types: List<DataTypeImpl> by lazy {
        listOf(waxLifeDataType, waxDistDataType)
    }

    override fun onCreate() {
        super.onCreate()
        repository = WaxRepository(applicationContext)
        karooSystem = KarooSystemService(applicationContext)
        createNotificationChannel()

        val filter = IntentFilter(WaxWatchConstants.ACTION_STATE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(updateReceiver, filter)
        }

        karooSystem.connect { connected ->
            if (connected) {
                karooSystem.addConsumer(OnStreamState.StartStreaming(io.hammerhead.karooext.models.DataType.Type.DISTANCE)) { event: OnStreamState ->
                    if (event.state is StreamState.Streaming) {
                        val stream = event.state as StreamState.Streaming
                        if (stream.dataPoint.dataTypeId == io.hammerhead.karooext.models.DataType.Type.DISTANCE) {
                            val distanceValue = stream.dataPoint.values[io.hammerhead.karooext.models.DataType.Field.SINGLE]
                            distanceValue?.let {
                                updateDistance(it)
                            }
                        }
                    }
                }

                karooSystem.addConsumer { event: io.hammerhead.karooext.models.ActiveRideProfile ->
                    val profileName = event.profile.name
                    Log.d("WaxWatch", "ActiveRideProfile observed: $profileName")
                    activeProfileName = profileName
                    refreshActiveProfileData()
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
        val waxState = repository.getWaxState(profileName) ?: run {
            val newMax = repository.getBaseWaxLifeMeters()
            val newState = net.shinytoaster.waxwatch.data.WaxState(profileName, newMax, newMax)
            repository.saveWaxState(newState)
            newState
        }
        broadcastUpdate(waxState.remainingDistanceMeters, waxState.remainingPercentage)
    }

    private fun updateDistance(newTotalDistance: Double) {
        val currentProfileId = activeProfileName ?: return
        if (lastDistanceMeters == 0.0) {
            lastDistanceMeters = newTotalDistance
            return
        }

        val distanceDelta = newTotalDistance - lastDistanceMeters
        if (distanceDelta > 0) {
            val state = repository.getWaxState(currentProfileId) ?: return
            
            val waxLifeConsumed = distanceDelta * state.surfaceType.multiplier
            val newRemaining = state.remainingDistanceMeters - waxLifeConsumed
            
            state.remainingDistanceMeters = maxOf(0.0, newRemaining)
            
            val percentage = state.remainingPercentage
            broadcastUpdate(state.remainingDistanceMeters, percentage)
            
            if (!state.alertTriggered) {
                val threshold = repository.getAlertThresholdPercent()
                if (percentage < threshold) {
                    sendAlertNotification(currentProfileId)
                    state.alertTriggered = true
                }
            }
            
            repository.saveWaxState(state)
        }
        
        lastDistanceMeters = newTotalDistance
    }

    private fun broadcastUpdate(remainingMeters: Double, percentage: Double) {
        waxLifeDataType.broadcastWaxLife(percentage)
        waxDistDataType.broadcastRemainingMeters(remainingMeters)
    }

    private fun sendAlertNotification(profileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
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
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(updateReceiver)
    }
}
