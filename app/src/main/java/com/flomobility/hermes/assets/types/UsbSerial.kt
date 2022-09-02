package com.flomobility.hermes.assets.types

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.Message
import com.felhr.usbserial.UsbSerialDevice
import com.felhr.usbserial.UsbSerialInterface
import com.flomobility.hermes.api.model.Raw
import com.flomobility.hermes.assets.AssetState
import com.flomobility.hermes.assets.AssetType
import com.flomobility.hermes.assets.BaseAsset
import com.flomobility.hermes.assets.BaseAssetConfig
import com.flomobility.hermes.common.Result
import com.flomobility.hermes.other.Constants
import com.flomobility.hermes.other.GsonUtils
import com.flomobility.hermes.other.handleExceptions
import org.zeromq.SocketType
import org.zeromq.ZContext
import org.zeromq.ZMQ
import timber.log.Timber
import zmq.ZError
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

class UsbSerial : BaseAsset {

    private var _id: String = ""

    private val _config = Config()

    private var _state = AssetState.IDLE

    private var usbSerialDevice: UsbSerialDevice? = null

    private var usbManager: UsbManager? = null

    private var usbDevice: UsbDevice? = null

    private var usbDeviceConnection: UsbDeviceConnection? = null

    private var usbHandlerThread: UsbHandlerThread? = null

    private var readerThread: ReaderThread? = null

    private var writerThread: WriterThread? = null

    private val delimiter: Byte
        get() = (_config.delimiter.value as String).toCharArray()[0].code.toByte()

    private val deviceOpenMutex = ReentrantLock(true)

    companion object {
        fun create(id: String, usbDevice: UsbDevice, usbManager: UsbManager): UsbSerial {
            Timber.d("Creating usb asset on ${Thread.currentThread().name}")
            return UsbSerial().apply {
                this.usbDevice = usbDevice
                this._id = id
                this.usbManager = usbManager
                this.usbHandlerThread = UsbHandlerThread()
                this.usbHandlerThread?.start()
            }
        }

        private const val BUFFER_SIZE_IN_BYTES = 2048
        private const val MSG_SYNC_OPEN_USB_SERIAL = 1
        private const val MSG_SYNC_CLOSE_USB_SERIAL = 2
    }

    override val id: String
        get() = _id

    override val type: AssetType
        get() = AssetType.USB_SERIAL

    override val config: BaseAssetConfig
        get() = _config

    override val state: AssetState
        get() = _state

    override fun updateConfig(config: BaseAssetConfig): Result {
        if (config !is UsbSerial.Config) {
            return Result(success = false, message = "unknown config type")
        }
        this._config.apply {
            baudRate.value = config.baudRate.value
            delimiter.value = config.delimiter.value
            portPub = config.portPub
            portSub = config.portSub
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
        Timber.d("Starting usb asset on ${Thread.currentThread().name}")
        // close usb serial and open it with new baud rate
        handleExceptions(catchBlock = { e ->
            return Result(success = false, message = e.message ?: Constants.UNKNOWN_ERROR_MSG)
        }) {

            usbDeviceConnection = usbManager?.openDevice(usbDevice)
            if (usbDeviceConnection == null) {
                return Result(success = true, message = "Couldn't open usb device $id")
            }
            usbSerialDevice = UsbSerialDevice.createUsbSerialDevice(usbDevice, usbDeviceConnection)

            usbHandlerThread?.syncOpen()

            return Result(success = true)
        }
        return Result(success = false, message = Constants.UNKNOWN_ERROR_MSG)
    }

    override fun stop(): Result {
        Timber.d("Stopping usb asset on ${Thread.currentThread().name}")
        handleExceptions(catchBlock = { e ->
            return Result(success = false, message = e.message ?: Constants.UNKNOWN_ERROR_MSG)
        }) {
            readerThread?.interrupt?.set(true)
            writerThread?.interrupt?.set(true)

            usbHandlerThread?.syncClose()

            readerThread?.join()
            writerThread?.join()

            readerThread = null
            writerThread = null

            return Result(success = true)
        }
        return Result(success = false, message = Constants.UNKNOWN_ERROR_MSG)
    }

    override fun destroy() {
        Timber.d("Destroying usb asset on ${Thread.currentThread().name}")
        usbDevice = null
        usbManager = null
        usbSerialDevice = null
        usbDeviceConnection = null
        readerThread = null
        writerThread = null

        usbHandlerThread?.kill()
        usbHandlerThread?.join()
        usbHandlerThread = null
    }

    inner class ReaderThread : Thread() {

        lateinit var socket: ZMQ.Socket

        val interrupt = AtomicBoolean(false)

        init {
            name = "${type.alias}-${this@UsbSerial.id}-reader-thread"
        }

        override fun run() {
            val address = "tcp://*:${config.portPub}"
            deviceOpenMutex.lock()
            Timber.d("${type.alias}-${this@UsbSerial.id} $usbSerialDevice")
            val inputStream = usbSerialDevice?.inputStream
            inputStream?.setTimeout(10)
            deviceOpenMutex.unlock()

            val context = ZContext()
            try {
                context.use { ctx ->
                    socket = ctx.createSocket(SocketType.PUB)
                    socket.bind(address)
                    while (!interrupt.get()) {
                        try {
                            val bytes = ByteArray(size = BUFFER_SIZE_IN_BYTES)
                            val ret = inputStream?.read(bytes)
                            if (ret == -1) {
//                                Timber.d("Error reading from ${type.alias}-$id")
                                continue
                            }
                            val msgStr = String(bytes)
                            var msg = ""
                            msgStr.forEach { ch ->
                                if (ch.code.toByte() == delimiter) {
                                    socket.sendRaw(msg.toByteArray(ZMQ.CHARSET), 0)
                                }
                                msg += ch
                            }
                        } catch (e: InterruptedException) {
                            Timber.i("${type.alias}-${this@UsbSerial.id} publisher on port:${_config.portPub} closed")
                        } catch (e: Exception) {
                            Timber.e(e)
                        }
                    }
                }
                Timber.i("${type.alias}-${this@UsbSerial.id} publisher on port:${_config.portPub} closed")
            } catch (e: ZError.IOException) {
                Timber.e("Error in closing ZMQ context for ${type.alias}-${this@UsbSerial.id} publisher on port:${_config.portPub}")
                return
            } catch (e: Exception) {
                Timber.e(e)
                return
            }
        }

        private fun ZMQ.Socket.sendRaw(bytes: ByteArray, flags: Int = 0) {
            val rawData = Raw(data = String(bytes, ZMQ.CHARSET))
            Timber.d("${this@UsbSerial.type.alias}-${this@UsbSerial.id} Sending raw data : $rawData")
            send(GsonUtils.getGson().toJson(rawData), flags)
        }

    }

    inner class WriterThread : Thread() {

        lateinit var socket: ZMQ.Socket

        val interrupt = AtomicBoolean(false)

        init {
            name = "${type.alias}-$id-writer-thread"
        }

        override fun run() {
            // TODO get ip from subscribe request
            val address = "tcp://192.168.43.192:${config.portSub}"
            Timber.d("[Sub Port] : $address")
            deviceOpenMutex.lock()
            val outputStream = usbSerialDevice?.outputStream
            outputStream?.setTimeout(50)
            deviceOpenMutex.unlock()

            val context = ZContext()
            try {
                context.use { ctx ->
                    socket = ctx.createSocket(SocketType.SUB)
                    socket.connect(address)
                    socket.subscribe("")
                    while (!interrupt.get()) {
                        try {
                            val recvBytes = socket.recv(ZMQ.DONTWAIT) ?: continue
                            val rawData = GsonUtils.getGson().fromJson<Raw>(
                                String(recvBytes, ZMQ.CHARSET),
                                Raw.type
                            )
                            Timber.d("[USB-Serial] : Data sending to usb_serial asset-> $rawData")
                            val bytes = "${rawData.data}\n".toByteArray(ZMQ.CHARSET)
                            outputStream?.write(bytes)
                        } catch (e: Exception) {
                            Timber.e(e)
                            return
                        }
                    }
                }
                Timber.i("${type.alias}-${this@UsbSerial.id} subscriber on port:${_config.portSub} closed")
            } catch (e: ZError.IOException) {
                Timber.e("Error in closing ZMQ context for ${type.alias}-${this@UsbSerial.id} subscriber on port:${_config.portSub}")
                return
            } catch (e: Exception) {
                Timber.e(e)
                return
            }
        }
    }

    inner class UsbHandlerThread: Thread() {

        init {
            name = "${this@UsbSerial.type.alias}-${this@UsbSerial.id}-handler-thread"
        }

        private var handler: UsbHandler? = null

        override fun run() {
            try {
                Looper.prepare()
                handler = UsbHandler(Looper.myLooper() ?: return)
                Looper.loop()
            } catch (e: Exception) {
                Timber.e("Exception in $name caught : $e")
            } finally {
                handler = null
            }
        }

        fun syncOpen() {
            handler?.sendMessage(MSG_SYNC_OPEN_USB_SERIAL)
        }

        fun syncClose() {
            handler?.sendMessage(MSG_SYNC_CLOSE_USB_SERIAL)
        }

        fun kill() {
            handler?.sendMessage(Constants.SIG_KILL_THREAD)
        }

        inner class UsbHandler(private val myLooper: Looper): Handler(myLooper) {
            override fun handleMessage(msg: Message) {
                when(msg.what) {
                    MSG_SYNC_OPEN_USB_SERIAL -> {
                        Timber.d("Opening device on ${Thread.currentThread().name}")
                        deviceOpenMutex.lock()
                        usbSerialDevice!!.apply {
                            if (syncOpen()) {
                                setBaudRate(_config.baudRate.value as Int)
                                setDataBits(UsbSerialInterface.DATA_BITS_8)
                                setParity(UsbSerialInterface.PARITY_NONE)
                                setStopBits(UsbSerialInterface.STOP_BITS_1)
                                setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF)
                            }
                        }
                        deviceOpenMutex.unlock()

                        readerThread = ReaderThread()
                        readerThread?.start()

                        writerThread = WriterThread()
                        writerThread?.start()
                    }
                    MSG_SYNC_CLOSE_USB_SERIAL -> {
                        deviceOpenMutex.lock()
                        usbSerialDevice?.syncClose()
                        deviceOpenMutex.unlock()
                    }
                    Constants.SIG_KILL_THREAD -> {
                        myLooper.quitSafely()
                    }
                }
            }

            fun sendMessage(what: Int, obj: Any? = null) {
                sendMessage(obtainMessage(what, obj))
            }

        }
    }

    class Config : BaseAssetConfig() {

        val baudRate = Field<Int>()

        val delimiter = Field<String>()

        init {
            baudRate.range = listOf(
                300,
                600,
                1200,
                2400,
                4800,
                9600,
                19200,
                38400,
                57600,
                115200,
                230400,
                460800,
                921600
            )
            baudRate.name = "baud"
            baudRate.value = DEFAULT_BAUD_RATE

            delimiter.range = listOf("\n", ",", ";", "\r", "\n", "\t")
            delimiter.name = "delimiter"
            delimiter.value = DEFAULT_DELIMITER
        }

        companion object {
            private const val DEFAULT_BAUD_RATE = 115200
            private const val DEFAULT_DELIMITER = "\n"
        }

        override fun getFields(): List<Field<*>> {
            return listOf(baudRate, delimiter)
        }
    }

}