package com.flomobility.hermes.assets.types.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Base64
import com.flomobility.hermes.api.model.Raw
import com.flomobility.hermes.assets.AssetState
import com.flomobility.hermes.assets.AssetType
import com.flomobility.hermes.assets.BaseAssetConfig
import com.flomobility.hermes.common.Result
import com.flomobility.hermes.other.Constants
import com.flomobility.hermes.other.Constants.SOCKET_BIND_DELAY_IN_MS
import com.flomobility.hermes.other.GsonUtils
import com.flomobility.hermes.other.handleExceptions
import com.flomobility.hermes.other.toByteArray
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usbcameracommon.CameraCallback
import com.serenegiant.usbcameracommon.UVCCameraHandler
import org.apache.commons.collections4.queue.CircularFifoQueue
import org.zeromq.SocketType
import org.zeromq.ZContext
import org.zeromq.ZMQ
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.*
import kotlin.Exception
import kotlin.system.measureTimeMillis

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

    private var streamingThread: StreamingThread? = null

    private val callbacks = mutableListOf<FrameCallback>()

    private var shouldCompress = true


    private val frames = CircularFifoQueue<ByteBuffer>(5)

    object Builder {
        fun createNew(id: String): UsbCamera {
            return UsbCamera().apply {
                this._id = id
                this.streamingThread = StreamingThread()
                this.streamingThread?.start()
            }
        }
    }

    private val cameraCallback = object : CameraCallback {
        override fun onOpen() {
            Timber.i("[$name] - Closing asset")
        }

        override fun onClose() {
            Timber.i("[$name] - Closing asset")
        }

        override fun onStartPreview() {
            Timber.i("[$name] - Started preview")
            this@UsbCamera.camThread?.camera?.setFrameCallback({ byteBuffer ->
                callbacks.forEach { cb ->
                    cb.onFrame(byteBuffer)
                }
            }, UVCCamera.PIXEL_FORMAT_NV21)
        }

        override fun onStopPreview() {
            Timber.i("[$name] - Stopped preview")
        }

        override fun onStartRecording() {
            /*NO-OP*/
        }

        override fun onStopRecording() {
            /*NO-OP*/
        }

        override fun onError(e: Exception?) {
            Timber.e("[$name] : $e")
        }
    }

    private val frameCallback = object : FrameCallback {
        override fun onFrame(byteBuffer: ByteBuffer) {
            streamingThread?.publishFrame(byteBuffer)
            /*val duplicate = byteBuffer.duplicate()
            Timber.d("${byteBuffer.hashCode()} & ${duplicate.hashCode()}")
            frames.add(duplicate)*/
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
        shouldCompress =
            (this._config.stream.value as Config.Stream).pixelFormat == Config.Stream.PixelFormat.MJPEG
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
        handleExceptions(catchBlock = { e ->
            _state = AssetState.IDLE
            Result(success = false, message = e.message ?: Constants.UNKNOWN_ERROR_MSG)
        }) {
            _state = AssetState.STREAMING
            val stream = _config.stream.value
            streamingThread?.updateAddress()
            registerCallback(frameCallback)
            camThread?.setStreamingParams(
                stream.width,
                stream.height,
                stream.fps,
                stream.pixelFormat.uvcCode,
                1f
            )
            camThread?.addCallback(this.cameraCallback)
            camThread?.startPreview()
            return Result(success = true)
        }
        return Result(success = false, message = Constants.UNKNOWN_ERROR_MSG)
    }

    override fun stop(): Result {
        handleExceptions(catchBlock = { e ->
            _state = AssetState.STREAMING
            Result(success = false, message = e.message ?: Constants.UNKNOWN_ERROR_MSG)
        }) {
            _state = AssetState.IDLE
            camThread?.stopPreview()
            unRegisterCallback(frameCallback)
            Timber.d("[$name]-Stopped")
        }
        return Result(success = false, message = Constants.UNKNOWN_ERROR_MSG)
    }

    override fun destroy() {
        streamingThread?.kill()
        camThread?.close()
        camThread = null
        streamingThread = null
    }

    fun registerCallback(cb: FrameCallback) {
        callbacks.add(cb)
    }

    fun unRegisterCallback(cb: FrameCallback) {
        callbacks.remove(cb)
    }

    fun setCameraThread(cameraHandler: UVCCameraHandler) {
        this.camThread = cameraHandler
    }

    inner class StreamingThread : Thread() {

        init {
            name = "${this@UsbCamera.name}-streaming-thread"
        }

        private var handler: StreamingThreadHandler? = null

        private lateinit var socket: ZMQ.Socket

        private var address = ""

        fun updateAddress() {
            address = "tcp://*:${_config.portPub}"
        }

        override fun run() {
            while (address.isEmpty()) {
                continue
            }
            Timber.i("[${this@UsbCamera.name}] - Starting Publisher on $address")
            try {
                ZContext().use { ctx ->
                    socket = ctx.createSocket(SocketType.PUB)
                    socket.bind(address)
                    sleep(SOCKET_BIND_DELAY_IN_MS)
                    Looper.prepare()
                    handler = StreamingThreadHandler(Looper.myLooper() ?: return)
                    Looper.loop()
                    /*while (!Thread.currentThread().isInterrupted) {
                        if (frames.size == 0) continue
                        val frame = frames.poll() ?: return
                        frame.rewind()
                        val bytes = ByteArray(frame.remaining())
                        frame.get(bytes)
                        if (bytes.isEmpty()) {
                            return
                        }
                        frame.clear()
                        Timber.d("Frame : ${bytes.size}")
                        socket.send(
                            bytes,
                            ZMQ.DONTWAIT
                        )
                    }*/
                }
                socket.unbind(address)
                sleep(SOCKET_BIND_DELAY_IN_MS)
                Timber.i("[${this@UsbCamera.name}] - Stopping Publisher on $address")
            } catch (e: Exception) {
                Timber.e(e)
            }
        }

        fun publishFrame(frame: ByteBuffer) {
            handler?.sendMsg(MSG_STREAM_FRAME, frame)
        }

        fun kill() {
            handler?.sendMsg(Constants.SIG_KILL_THREAD)
        }

        inner class StreamingThreadHandler(private val myLooper: Looper) : Handler(myLooper) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    MSG_STREAM_FRAME -> {
//                        Thread.sleep(200)
                        val elapsed = measureTimeMillis {
                            val frame = msg.obj as ByteBuffer
                            socket.sendByteBuffer(frame, ZMQ.DONTWAIT)
                        }
                        Timber.d("$elapsed")

                        // 1. convert to byte array
/*                        frame.rewind()
                        var bytes = ByteArray(frame.remaining())
                        frame.get(bytes)
                        if (bytes.isEmpty()) {
                            return
                        }*/
                        // 2. compression according to quality
/*                        if (shouldCompress) {
                            bytes = compressImage(bytes)
                        }*/

                        // 3. convert to base64
//                        val base64 = bytes.toBase64()
//                        val base64 = Base64.encodeToString(bytes, Base64.NO_CLOSE)
//                        val rawData = Raw(data = base64)
//                        socket.sendByteBuffer(frame, ZMQ.DONTWAIT)

                    }
                    Constants.SIG_KILL_THREAD -> {
                        myLooper.quitSafely()
                    }
                }
            }

            fun sendMsg(what: Int, obj: Any? = null) {
                sendMessage(obtainMessage(what, obj))
            }
        }
    }

    // utility functions
    private fun compressImage(bytes: ByteArray): ByteArray {
        return try {
            val bitmap =
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options())
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, _config.compressionQuality.value, baos)
            baos.toByteArray()
        } catch (e: Exception) {
            Timber.e("Unable to compress frame : $e")
            bytes
        }
    }


    private fun ByteArray.toBase64(): String {
        return Base64.encodeToString(this, Base64.NO_CLOSE)
    }

    companion object {
        private const val MSG_STREAM_FRAME = 9
    }

    interface FrameCallback {
        fun onFrame(byteBuffer: ByteBuffer)
    }

}