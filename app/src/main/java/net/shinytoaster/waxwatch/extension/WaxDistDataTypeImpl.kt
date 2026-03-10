package net.shinytoaster.waxwatch.extension

import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.shinytoaster.waxwatch.data.DistanceUnit
import net.shinytoaster.waxwatch.data.WaxRepository
import net.shinytoaster.waxwatch.domain.WaxCalculator

/**
 * Streams the remaining wax distance to a Karoo data field.
 *
 * Uses the same StateFlow/coroutine pattern as [WaxLifeDataTypeImpl] — see that class for
 * a full explanation of why this pattern is required by the Karoo SDK.
 *
 * Distance conversion to the user's preferred unit (km/mi) is applied on every emission
 * so the Karoo display always shows the correct localised value.
 */
class WaxDistDataTypeImpl(
    private val repository: WaxRepository,
    private val stateFlow: StateFlow<Pair<Double, Double>?>,
) : DataTypeImpl("waxwatch", "wax_life_dist") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            stateFlow.collect { state ->
                if (state != null) {
                    val (remainingMeters, _) = state
                    val resolvedUnit = repository.resolveDistanceUnit()
                    val displayVal = if (resolvedUnit == DistanceUnit.MILES) {
                        WaxCalculator.metersToMiles(remainingMeters)
                    } else {
                        WaxCalculator.metersToKm(remainingMeters)
                    }
                    emitter.onNext(
                        StreamState.Streaming(
                            DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to displayVal))
                        )
                    )
                } else {
                    emitter.onNext(StreamState.Searching)
                }
            }
        }
        emitter.setCancellable { job.cancel() }
    }
}
