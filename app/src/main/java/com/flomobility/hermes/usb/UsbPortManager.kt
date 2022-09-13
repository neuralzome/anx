package com.flomobility.hermes.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.flomobility.hermes.assets.AssetManager
import com.flomobility.hermes.assets.AssetType
import com.flomobility.hermes.assets.types.UsbSerial
import com.flomobility.hermes.usb.serial.UsbSerialManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class UsbPortManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val assetManager: AssetManager,
    private val usbManager: UsbManager,
    private val usbSerialManager: UsbSerialManager
) {

    private val usbReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(arg0: Context, arg1: Intent) {
            if (arg1.action == ACTION_USB_ATTACHED) {
                val usbDevice = arg1.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                Timber.i("USB device attached $usbDevice")
                if(usbDevice == null) {
                    Timber.e("No usb device attached")
                    return
                }
                usbDeviceCallback.onAttach(usbDevice)
            } else if (arg1.action == ACTION_USB_DETACHED) {
                val usbDevice = arg1.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if(usbDevice == null) {
                    Timber.e("No usb device detached")
                    return
                }
                usbDeviceCallback.onDetach(usbDevice)
                Timber.d("$usbDevice disconnected")
            }
        }
    }

    private val usbDeviceCallback = object : UsbDeviceCallback {
        override fun onAttach(usbDevice: UsbDevice?) {
            if (usbDevice?.getDeviceType() == UsbDeviceType.SERIAL) {
                val serialPort = usbSerialManager.registerSerialDevice(usbDevice.deviceId)
                if(serialPort == -1) {
                    Timber.e("No ports available for ${usbDevice.deviceName}")
                    return
                }
                assetManager.addAsset(UsbSerial.create("$serialPort",usbDevice, usbManager))
            }
        }

        override fun onDetach(usbDevice: UsbDevice?) {
            if (usbDevice?.getDeviceType() == UsbDeviceType.SERIAL) {
                val serialPort = usbSerialManager.unRegisterSerialDevice(usbDevice.deviceId)
                if (serialPort == -1) {
                    Timber.e("Couldn't un-register ${usbDevice.deviceName}")
                    return
                }
                assetManager.removeAsset("$serialPort", AssetType.USB_SERIAL)
            }
        }
    }

    private fun attachDevices() {
        Timber.i("Looking for devices")
        val devices = usbManager.deviceList ?: return
        devices.values.sortedBy { it.deviceId }.forEach { usbDevice ->
            usbDeviceCallback.onAttach(usbDevice)
        }
    }

    fun init() {
        val filter = IntentFilter()
        filter.addAction(ACTION_USB_DETACHED)
        filter.addAction(ACTION_USB_ATTACHED)
        context.registerReceiver(usbReceiver, filter)

//        if(usbSerialManager.isRegistryEmpty()) {
            attachDevices()
//        }
    }
    companion object {
        private const val ACTION_USB_ATTACHED = UsbManager.ACTION_USB_DEVICE_ATTACHED
        private const val ACTION_USB_DETACHED = UsbManager.ACTION_USB_DEVICE_DETACHED
    }

}