package com.flomobility.hermes.other

import java.nio.ByteBuffer
import kotlin.reflect.KClass

/**
 * A generic function to handle exceptions
 *
 * */
inline fun <T> handleExceptions(
    vararg exceptions: KClass<out Exception>,
    catchBlock: ((Exception) -> Unit) = { it.printStackTrace() },
    block: () -> T?
): Exception? {
    return try {
        block()
        null
    } catch (e: Exception) {
        val contains = exceptions.find {
            it.isInstance(e)
        }
        contains?.let {
            return it.javaObjectType.cast(e)
        }
        catchBlock(e)
        e
    }
}

/**
 * converts a [ByteBuffer] object to a byte array.
 *
 * If array doesn't exist, empty array is returned
 * @return data from buffer in an array
 * */
fun ByteBuffer.toByteArray(): ByteArray {
    try {
        rewind()
        if(hasArray())
            return this.array()
        val bytes = ByteArray(remaining())
        get(bytes)
        return bytes
    } catch (e: Exception) {
        return ByteArray(0)
    }
}