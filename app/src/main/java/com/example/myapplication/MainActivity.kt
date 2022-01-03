package com.example.myapplication

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.icu.text.DateFormat
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.azure.messaging.eventhubs.EventData
import com.azure.messaging.eventhubs.EventDataBatch
import com.azure.messaging.eventhubs.EventHubClientBuilder
import com.azure.messaging.eventhubs.EventHubProducerClient
import com.google.android.gms.location.*
import com.google.gson.Gson
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.Charset


class MainActivity : AppCompatActivity(), SensorEventListener {

    //buttons to start (stand/walk/run are used for "learning" creating labeled activities)
    private lateinit var clickButton: Button
    private lateinit var clickStandButton: Button
    private lateinit var clickWalkButton: Button
    private lateinit var clickRunButton: Button
    private var testLabels = arrayOf("standing", "walking", "running")

    //used to conditionally allow sending data
    private var SendingData = false

    //the label that is used (if null label, is used for "testing" if not null is used for "learning"
    private var labelUsed: String? = null

    //the timestamp of when the activity started (used to identify it)
    private var activityTimestamp: Long = 0

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var latestLocation: Location? = null


    private lateinit var sensorManager: SensorManager
    private var accelometerSensor: Sensor? = null
    private var gyroscopeSensor: Sensor? = null
    private var stepsSensor: Sensor? = null
    lateinit var eventDataBatch: EventDataBatch

    private final var fromSensor = true

    object RepeatHelper {
        fun repeatDelayed(delay: Long, todo: () -> Unit) {
            val handler = Handler()
            handler.postDelayed(object : Runnable {
                override fun run() {
                    todo()
                    handler.postDelayed(this, delay)
                }
            }, delay)
        }
    }

    //steps stuff
    private var totalSteps = 0f
    private var previousTotalSteps = 0f
    private lateinit var textSteps: TextView
    var connectionString =
        "Endpoint=sb://bbig-data-events.servicebus.windows.net/;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=sZU1kA0rIVdPjcC26gm23wZnSsWI5MptBJPshPPo0+U="
    var eventHubName = "from-phone"
    private lateinit var producer: EventHubProducerClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult?) {
                if (locationResult != null) {
                    latestLocation = locationResult.locations.last()
                }
            }
        }


        producer = EventHubClientBuilder()
            .connectionString(connectionString, eventHubName)
            .buildProducerClient()

        eventDataBatch = producer.createBatch()

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        textSteps = findViewById(R.id.tv_stepsTaken)
        //eventhub test
        clickButton = findViewById(R.id.clickButton)
        clickStandButton = findViewById(R.id.clickStandButton)
        clickWalkButton = findViewById(R.id.clickWalkButton)
        clickRunButton = findViewById(R.id.clickRunButton)

        clickButton.setOnClickListener {
            labelUsed = null
            BaseButtonStartPress()
        }
        clickStandButton.setOnClickListener {
            labelUsed = testLabels[0]
            BaseButtonStartPress()
        }
        clickWalkButton.setOnClickListener {
            labelUsed = testLabels[1]
            BaseButtonStartPress()
        }
        clickRunButton.setOnClickListener {
            labelUsed = testLabels[2]
            BaseButtonStartPress()
        }
        setUpSensorStuff()
        loadData()
        resetSteps()
    }

    private fun BaseButtonStartPress() {
        val delay = 1000L
        SendingData = true
        activityTimestamp = System.currentTimeMillis()
        eventDataBatch = producer.createBatch()
        RepeatHelper.repeatDelayed(delay) {
            SendSensorDataToEH()
            SetBatchToEH()
        }

        val alert = android.app.AlertDialog.Builder(this)
        alert.setTitle(
            "Started $labelUsed time:" + DateFormat.getTimeInstance()
                .format(activityTimestamp)
        )

        alert.setNeutralButton("Finish") { _, _ ->
            SendingData = false
            labelUsed = null
            activityTimestamp = 0
        }

        alert.show()
    }

    private fun SendSensorDataToEH() {
        val threadWithRunnable =
            Thread(UdpReader(this.producer, this.activityTimestamp, this.labelUsed))
        threadWithRunnable.start()
    }

    private fun SetBatchToEH() {
        if (eventDataBatch.count > 0 && SendingData) {
            var copyBatch = eventDataBatch
            eventDataBatch = producer.createBatch()
            producer.send(copyBatch)
        }

    }

    private fun setUpSensorStuff() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        accelometerSensor = sensorManager.getDefaultSensor((Sensor.TYPE_ACCELEROMETER))
        gyroscopeSensor = sensorManager.getDefaultSensor((Sensor.TYPE_GYROSCOPE))
        stepsSensor = sensorManager.getDefaultSensor((Sensor.TYPE_STEP_COUNTER))
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val tsLong = System.currentTimeMillis()
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER)
            handleAcceleration(event, tsLong)
        if (event?.sensor?.type == Sensor.TYPE_STEP_COUNTER)
            handleSteps(event, tsLong)
        if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE)
            HandleGyroscope(event, tsLong)

    }

    private fun HandleGyroscope(event: SensorEvent, tsLong: Long) {
        if (SendingData)
            eventDataBatch.tryAdd(EventData(Gson().toJson(object {
                val type = DataType.Gyroscope
                val values = event.values
                val timestamp = tsLong
                val activityStartTimestamp = activityTimestamp
                val label = labelUsed
                val location = getSimpleLocationObject(latestLocation)
            })))
    }

    private fun getSimpleLocationObject(latestLocation: Location?): Any {
        return object {
            val latitude = latestLocation?.latitude
            val longitude = latestLocation?.longitude
            val altitude = latestLocation?.altitude
            val accuracy = latestLocation?.accuracy
        }
    }

    private fun handleSteps(event: SensorEvent, tsLong: Long) {
        totalSteps = event.values[0]
        val currentSteps = totalSteps.toInt() - previousTotalSteps.toInt()
        textSteps.text = ("$currentSteps")

        if (SendingData)
            eventDataBatch.tryAdd(EventData(Gson().toJson(object {
                val type = DataType.Steps
                val values = event.values
                val timestamp = tsLong
                val activityStartTimestamp = activityTimestamp
                val label = labelUsed
                val location = getSimpleLocationObject(latestLocation)
            })))
    }

    private fun handleAcceleration(event: SensorEvent, tsLong: Long) {
        if (SendingData)
            eventDataBatch.tryAdd(EventData(Gson().toJson(object {
                val type = DataType.Accelometer
                val values = event.values
                val timestamp = tsLong
                val activityStartTimestamp = activityTimestamp
                val label = labelUsed
                val location = getSimpleLocationObject(latestLocation)
            })))

    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        return
    }

    override fun onResume() {
        super.onResume()
        // Register a listener for the sensor.
        sensorManager.registerListener(this, accelometerSensor, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.registerListener(this, gyroscopeSensor, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.registerListener(this, stepsSensor, SensorManager.SENSOR_DELAY_UI)
        startLocationUpdates()

    }

    private fun createLocationRequest(): LocationRequest {
        return LocationRequest.create().apply {
            interval = 10000
            fastestInterval = 5000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        fusedLocationClient.requestLocationUpdates(
            createLocationRequest(),
            locationCallback,
            Looper.getMainLooper()
        )
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    private fun resetSteps() {
        val tv_stepsTaken = findViewById<TextView>(R.id.tv_stepsTaken)
        tv_stepsTaken.setOnClickListener {
            // This will give a toast message if the user want to reset the steps
            Toast.makeText(this, "Long tap to reset steps", Toast.LENGTH_SHORT).show()
        }

        tv_stepsTaken.setOnLongClickListener {
            previousTotalSteps = totalSteps
            tv_stepsTaken.text = 0.toString()
            saveData()
            true
        }
    }

    //steps stuff
    private fun saveData() {
        val sharedPreferences = getSharedPreferences("myPrefs", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putFloat("steps", previousTotalSteps)
        editor.apply()
    }

    private fun loadData() {
        val sharedPreferences = getSharedPreferences("myPrefs", Context.MODE_PRIVATE)
        val savedNumber = sharedPreferences.getFloat("steps", 0f)

        previousTotalSteps = savedNumber
    }
}

class UdpReader : Runnable {

    private var producer: EventHubProducerClient
    private var activityTimestamp: Long = 0
    private var labelUsed: String? = null

    constructor(eventHubProducer: EventHubProducerClient, timestamp: Long, label: String?) {
        eventHubProducer.also { this.producer = it }
        activityTimestamp = timestamp
        labelUsed = label
    }

    public override fun run() {
        println("${Thread.currentThread()} has run.")
        var socket: DatagramSocket? = null

        try {
            val buffer = ByteArray(2048)
            socket = DatagramSocket(5001, InetAddress.getByName("192.168.150.70"))

            while (true) {
                //Keep a socket open to listen to all the UDP trafic that is destined for this port
                socket.broadcast = true
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val timestamp = System.currentTimeMillis()
                val data = String(packet.data, 0, packet.length, Charset.defaultCharset())
                val sensorData = Gson().fromJson(data, SensorData::class.java)
                var accelerometerData: EventData = EventData(Gson().toJson(object {
                    val type = DataType.Accelometer
                    val values = listOf(sensorData.Accel_X, sensorData.Accel_Y, sensorData.Accel_Z)
                    val timestamp = timestamp
                    val activityStartTimestamp = activityTimestamp
                    val label = labelUsed

                }))
                var gyroscopeData: EventData = EventData(Gson().toJson(object {
                    val type = DataType.Accelometer
                    val values = listOf(sensorData.Gyro_X, sensorData.Gyro_Y, sensorData.Gyro_Z)
                    val timestamp = timestamp
                    val activityStartTimestamp = activityTimestamp
                    val label = labelUsed
                }))
                this.producer.send(listOf(accelerometerData, gyroscopeData))

            }
        } catch (e: Exception) {
            println("Caught exception while receiving UDP data:" + e.toString())
            e.printStackTrace()
        } finally {
            socket?.close()
        }
    }
}