package com.example.myapplication

data class SensorData(
    var Accel_X: Long,
    var Accel_Y: Long,
    var Accel_Z: Long,
    var Gyro_X: Long,
    var Gyro_Y: Long,
    var Gyro_Z: Long,
    var Temp: Double
)