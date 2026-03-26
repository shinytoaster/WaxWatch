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

/**
 * Streams the current wax life percentage to a Karoo data field.
 *
 * The correct Karoo SDK pattern is to collect a StateFlow inside a coroutine launched
 * within startStream. The coroutine runs for the lifetime of the Karoo stream subscription
 * and is cancelled automatically when Karoo stops consuming the field. Any update to the
 * StateFlow (from app edits or ride distance events) is automatically pushed to the field
 * without needing to maintain a list of emitters.
 */
class WaxLifeDataTypeImpl(
    private val stateFlow: StateFlow<Triple<Double, Double, Boolean>?>,
) : DataTypeImpl("waxwatch", "wax_life_pct") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            stateFlow.collect { state ->
                if (state != null) {
                    val pct = state.second
                    emitter.onNext(
                        StreamState.Streaming(
                            DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to pct.coerceIn(0.0, 100.0)))
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
