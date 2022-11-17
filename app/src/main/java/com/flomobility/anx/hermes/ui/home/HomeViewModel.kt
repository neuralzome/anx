package com.flomobility.anx.hermes.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flomobility.anx.hermes.network.requests.InfoRequest
import com.flomobility.anx.hermes.network.responses.InfoResponse
import com.flomobility.anx.hermes.other.Event
import com.flomobility.anx.hermes.other.Resource
import com.flomobility.anx.hermes.repositories.FloRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: FloRepository
) : ViewModel() {

    private val _info = MutableLiveData<Event<Resource<InfoResponse>>>()
    val info: LiveData<Event<Resource<InfoResponse>>> = _info

    fun sendInfoRequest(info: InfoRequest) {
        _info.value = Event(Resource.Loading())
        viewModelScope.launch {
            _info.value = Event(repository.getInfo(info))
        }
    }

}