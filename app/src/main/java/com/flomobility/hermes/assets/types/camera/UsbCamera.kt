package com.flomobility.hermes.assets.types.camera

import android.hardware.usb.UsbDevice
import com.flomobility.hermes.assets.AssetState
import com.flomobility.hermes.assets.AssetType
import com.flomobility.hermes.assets.BaseAssetConfig
import com.flomobility.hermes.common.Result
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usbcameracommon.CameraCallback
import com.serenegiant.usbcameracommon.UVCCameraHandler
import timber.log.Timber
import java.nio.ByteBuffer

class UsbCamera : Camera() {

    private var _id: String = ""

    private val _config = Config()

    private var _state = AssetState.IDLE

    override val id: String
        get() = _id

    override val type: AssetType
        get() = AssetType.CAM

    override val config: BaseAssetConfig
        get() = _config

    override val state: AssetState
        get() = _state

    private var camThread: UVCCameraHandler? = null

    private val callbacks = mutableListOf<FrameCallback>()

    companion object {
        fun createNew(id: String): UsbCamera {
            return UsbCamera().apply {
                this._id = id
            }
        }
    }

    override fun updateConfig(config: BaseAssetConfig): Result {
        if (config !is Camera.Config) {
            return Result(success = false, message = "unknown config type")
        }
        this._config.apply {
            stream.value = config.stream.value
            compressionQuality.value = config.compressionQuality.value
            portPub = config.portPub
            portSub = config.portSub
            connectedDeviceIp = config.connectedDeviceIp
        }
        return Result(success = true)
    }

    override fun getDesc(): Map<String, Any> {
        val map = hashMapOf<String, Any>("id" to id)
        config.getFields().forEach { field ->
            map[field.name] = field.range
        }
        return map
    }

    override fun start(): Result {
        TODO("Not yet implemented")
    }

    override fun stop(): Result {
        TODO("Not yet implemented")
    }

    override fun destroy() {
        TODO("Not yet implemented")
    }

    fun registerCallback(cb: FrameCallback) {
        callbacks.add(cb)
    }

    fun unRegisterCallback(cb: FrameCallback) {
        callbacks.remove(cb)
    }

    fun setCameraThread(cameraHandler: UVCCameraHandler) {
        this.camThread = cameraHandler
        this.camThread?.addCallback(object : CameraCallback {
            override fun onOpen() {
                this@UsbCamera.camThread?.camera?.setFrameCallback({ byteBuffer ->
                    callbacks.forEach { cb ->
                        cb.onFrame(byteBuffer)
                    }
                }, UVCCamera.PIXEL_FORMAT_NV21)
            }

            override fun onClose() {
//                this@UsbCamera.state = State.IDLE
            }

            override fun onStartPreview() {
//                this@UsbCamera.state = State.STREAMING
            }

            override fun onStopPreview() {
//                this@UsbCamera.state = State.IDLE
            }

            override fun onStartRecording() {
                /*NO-OP*/
            }

            override fun onStopRecording() {
                /*NO-OP*/
            }

            override fun onError(e: Exception?) {
                Timber.e(e)
            }
        })
    }

    fun close() {
        this.camThread?.close()
    }

    interface FrameCallback {
        fun onFrame(byteBuffer: ByteBuffer)
        fun unRegister()
    }

}