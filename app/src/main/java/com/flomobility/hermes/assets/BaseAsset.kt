package com.flomobility.hermes.assets

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.flomobility.hermes.common.Result


abstract class BaseAsset {

    abstract val id: String

    abstract val type: AssetType

    abstract val config: BaseAssetConfig

    var canRegister = true

    val name: String
        get() = "${type.alias}-$id"

    private var _state = MutableLiveData(AssetState.IDLE)

    val state: AssetState
        get() = _state.value!!

    fun updateState(state: AssetState) {
        this._state.postValue(state)
    }

    fun getStateLiveData(): LiveData<AssetState> {
        return _state
    }

    abstract fun updateConfig(config: BaseAssetConfig): Result

    open fun getDesc(): Map<String, Any> {
        val map = hashMapOf<String, Any>("id" to id)
        config.getFields().forEach { field ->
            map[field.name] = field.range
        }
        return map
    }

    abstract fun start(): Result

    abstract fun stop(): Result

    abstract fun destroy()

}