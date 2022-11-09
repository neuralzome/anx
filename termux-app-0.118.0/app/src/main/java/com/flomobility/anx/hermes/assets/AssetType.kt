package com.flomobility.anx.hermes.assets

enum class AssetType(val alias: String) {
    IMU("imu"),
    GNSS("gnss"),
    USB_SERIAL("usb_serial"),
    CAM("camera"),
    CLASSIC_BT("classic_bt"),
    MIC("mic"),
    BLE("ble"),
    SPEAKER("speaker"),
    PHONE("phone"),
    UNK("unknown")
}

fun getAssetTypeFromAlias(alias: String) = when (alias) {
    AssetType.IMU.alias -> AssetType.IMU
    AssetType.GNSS.alias -> AssetType.GNSS
    AssetType.USB_SERIAL.alias -> AssetType.USB_SERIAL
    AssetType.CAM.alias -> AssetType.CAM
    AssetType.CLASSIC_BT.alias -> AssetType.CLASSIC_BT
    AssetType.BLE.alias -> AssetType.BLE
    AssetType.SPEAKER.alias -> AssetType.SPEAKER
    AssetType.PHONE.alias -> AssetType.PHONE
    else -> AssetType.UNK
}