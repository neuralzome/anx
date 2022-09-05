package com.flomobility.hermes.assets.types.camera

import com.flomobility.hermes.assets.BaseAsset
import com.flomobility.hermes.assets.BaseAssetConfig
import com.flomobility.hermes.other.GsonUtils
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.serenegiant.usb.UVCCamera

abstract class Camera : BaseAsset {

    class Config : BaseAssetConfig() {

        val stream = Field<Stream>(Stream::class.java)

        val compressionQuality = Field<Int>(Int::class.java)

        init {
            stream.name = "stream"
            stream.value = Stream.DEFAULT

            compressionQuality.name = "compression_quality"
            compressionQuality.value = DEFAULT_COMPRESSION_QUALITY
            compressionQuality.range = listOf(
                10, 25, 50, 75, 80, 85, 90, 95, 100
            )
        }

        fun loadStreams(streams: List<Stream>) {
            stream.range = streams
        }

        data class Stream(
            @SerializedName("fps")
            val fps: Int,
            @SerializedName("width")
            val width: Int,
            @SerializedName("height")
            val height: Int,
            @SerializedName("pixel_format")
            val pixelFormat: PixelFormat
        ) {
            enum class PixelFormat(val alias: String, val code: Int, val uvcCode: Int) {
                @SerializedName("mjpeg")
                MJPEG("mjpeg", 6, UVCCamera.FRAME_FORMAT_MJPEG),
                @SerializedName("yuyv")
                YUYV("yuyv", 4, UVCCamera.FRAME_FORMAT_YUYV),
                UNK("unkown", -1, -1)
            }

            companion object {
                val DEFAULT = Stream(
                    fps = 30,
                    width = 1920,
                    height = 1080,
                    pixelFormat = Stream.PixelFormat.MJPEG
                )

                fun getPixelFormatFromCode(code: Int): PixelFormat {
                    return when (code) {
                        PixelFormat.MJPEG.code -> PixelFormat.MJPEG
                        PixelFormat.YUYV.code -> PixelFormat.YUYV
                        else -> PixelFormat.UNK
                    }
                }
            }
        }

        override fun getFields(): List<Field<*>> {
            return listOf(stream, compressionQuality)
        }

        companion object {
            private const val DEFAULT_COMPRESSION_QUALITY = 90

            fun toStreamList(formatsJson: String): List<Stream> {
                val streamsList = mutableListOf<Stream>()
                val usbCameraFormats = GsonUtils.getGson()
                    .fromJson<UsbCameraFormats>(formatsJson, UsbCameraFormats.type)
                usbCameraFormats.formats.forEach { format ->
                    format.streams.forEach { camStream ->
                        streamsList.addAll(camStream.fps.map { fps ->
                            Stream(
                                width = camStream.width,
                                height = camStream.height,
                                fps = fps,
                                pixelFormat = Stream.getPixelFormatFromCode(format.type)
                            )
                        })
                    }
                }
                return streamsList
            }
        }

    }

    data class UsbCameraFormats(
        @SerializedName("formats")
        val formats: List<Format>
    ) {

        companion object {
            val type = object : TypeToken<UsbCameraFormats>() {}.type
        }

        data class Format(
            @SerializedName("index")
            val index: Int,
            @SerializedName("type")
            val type: Int,
            @SerializedName("default")
            val default: Int,
            @SerializedName("streams")
            val streams: List<Stream>
        ) {
            data class Stream(
                @SerializedName("width")
                val width: Int,
                @SerializedName("height")
                val height: Int,
                @SerializedName("fps")
                val fps: List<Int>
            )
        }
    }

}