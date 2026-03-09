package net.shinytoaster.waxwatch.extension

import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import net.shinytoaster.waxwatch.data.DistanceUnit
import net.shinytoaster.waxwatch.data.WaxRepository
import net.shinytoaster.waxwatch.domain.WaxCalculator

class WaxDistDataTypeImpl(
    private val repository: WaxRepository,
    private val getRemainingMeters: () -> Double?
) : DataTypeImpl("waxwatch", "wax_life_dist") {

    private val emitters = mutableListOf<Emitter<StreamState>>()

    override fun startStream(emitter: Emitter<StreamState>) {
        emitters.add(emitter)
        emitter.setCancellable {
            emitters.remove(emitter)
        }

        val meters = getRemainingMeters()
        if (meters != null) {
            broadcastRemainingMeters(meters)
        } else {
            emitter.onNext(StreamState.Searching)
        }
    }

    fun broadcastRemainingMeters(meters: Double) {
        val resolvedUnit = repository.resolveDistanceUnit()
        val displayVal = if (resolvedUnit == DistanceUnit.MILES) {
            WaxCalculator.metersToMiles(meters)
        } else {
            WaxCalculator.metersToKm(meters)
        }
        
        val event = StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to displayVal)))
        emitters.forEach { it.onNext(event) }
    }
}
