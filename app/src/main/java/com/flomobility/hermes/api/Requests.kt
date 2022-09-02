package com.flomobility.hermes.api

import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken


data class SubscribeRequest(
    @SerializedName("subscribe")
    val subscribe: Boolean
) {

    companion object {
        val type = object : TypeToken<SubscribeRequest>() {}.type
    }
}