package net.shinytoaster.waxwatch.extension

import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState

class WaxLifeDataTypeImpl(
    private val getCurrentPercentage: () -> Double?
) : DataTypeImpl("waxwatch", "wax_life_pct") {

    private val emitters = mutableListOf<Emitter<StreamState>>()

    override fun startStream(emitter: Emitter<StreamState>) {
        emitters.add(emitter)
        emitter.setCancellable {
            emitters.remove(emitter)
        }

        val pct = getCurrentPercentage()
        if (pct != null) {
            val clampedPct = maxOf(0.0, minOf(100.0, pct))
            emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to clampedPct))))
        } else {
            // Emitting Searching will show a loading/searching indicator instead of "No Sensor"
            emitter.onNext(StreamState.Searching)
        }
    }

    fun broadcastWaxLife(percentage: Double) {
        val clampedPct = maxOf(0.0, minOf(100.0, percentage))
        val event = StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to clampedPct)))
        emitters.forEach { it.onNext(event) }
    }
}
