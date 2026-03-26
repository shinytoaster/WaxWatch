package net.shinytoaster.waxwatch.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.shinytoaster.waxwatch.WaxWatchConstants
import net.shinytoaster.waxwatch.data.WaxRepository
import net.shinytoaster.waxwatch.extension.WaxWatchExtension

data class ProfileUiState(
    val id: String,
    val alertTriggered: Boolean,
    val remainingPct: Double,
    val remainingDistanceMeters: Double
)

class ProfileListViewModel(private val repository: WaxRepository) : ViewModel() {

    private val _profiles = MutableStateFlow<List<ProfileUiState>>(emptyList())
    val profiles: StateFlow<List<ProfileUiState>> = _profiles.asStateFlow()

    init {
        loadProfiles()
    }

    fun loadProfiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val ids = repository.getSavedProfileIds()
            val loaded = ids.mapNotNull { id ->
                repository.getWaxState(id)?.let { state ->
                    ProfileUiState(
                        id = state.profileId,
                        alertTriggered = state.alertTriggered,
                        remainingPct = state.remainingPercentage,
                        remainingDistanceMeters = state.remainingDistanceMeters
                    )
                }
            }
            _profiles.value = loaded
        }
    }

    fun resetWaxAlert(profileId: String, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val fullState = repository.getWaxState(profileId) ?: return@launch
            val newState = fullState.copy(alertTriggered = false)
            repository.saveWaxState(newState)
            
            val intent = Intent(context, WaxWatchExtension::class.java).apply {
                action = WaxWatchConstants.ACTION_STATE_UPDATED
                putExtra(WaxWatchConstants.EXTRA_PROFILE_ID, newState.profileId)
                putExtra(WaxWatchConstants.EXTRA_REMAINING_METERS, newState.remainingDistanceMeters)
                putExtra(WaxWatchConstants.EXTRA_MAX_LIFE_METERS, newState.maxLifeMeters)
            }
            context.startService(intent)
            
            loadProfiles()
        }
    }
}
