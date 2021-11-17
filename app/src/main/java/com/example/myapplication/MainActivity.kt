package com.example.myapplication

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.azure.messaging.eventhubs.EventData
import com.azure.messaging.eventhubs.EventDataBatch
import com.azure.messaging.eventhubs.EventHubClientBuilder
import com.azure.messaging.eventhubs.EventHubProducerClient
import com.google.gson.Gson
import java.lang.Math.abs
import java.util.*


enum class DataType {
    Accelometer, Gyroscope, Steps
}

private lateinit var textMaxesAcce: TextView


class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var clickButton: Button
    private lateinit var sensorManager: SensorManager
    private var accelometerSensor: Sensor? = null
    private var gyroscopeSensor: Sensor? = null
    private var stepsSensor: Sensor? = null
    private lateinit var textAcce: TextView
    private lateinit var textGyro: TextView
    private lateinit var textMaxesGyro: TextView
    private var maxes = FloatArray(3)
    private var maxesGyro = FloatArray(3)
    lateinit var eventDataBatch: EventDataBatch

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

        producer = EventHubClientBuilder()
            .connectionString(connectionString, eventHubName)
            .buildProducerClient()

        eventDataBatch = producer.createBatch()

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        textAcce = findViewById(R.id.tv_text_acce)
        textMaxesAcce = findViewById(R.id.tv_text_maxes_acce)

        textGyro = findViewById(R.id.tv_text_gyro)
        textMaxesGyro = findViewById(R.id.tv_text_maxes_gyro)

        textSteps = findViewById(R.id.tv_stepsTaken)
        //eventhub test
        clickButton = findViewById(R.id.clickButton)

        clickButton.setOnClickListener {

            val allEvents: List<EventData> = Arrays.asList(EventData("Foo"), EventData("Bar"))


        };
        setUpSensorStuff()
        loadData()
        resetSteps()

        val delay = 1000L
        RepeatHelper.repeatDelayed(delay) {
            SetBatchToEH()
        }
    }

    private fun SetBatchToEH() {
        var ri=eventDataBatch.count
        if (eventDataBatch.count > 0) {
            var copyBatch=eventDataBatch;
            eventDataBatch=producer.createBatch()
            var r=copyBatch.count
            var rr=eventDataBatch.count
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
        var tsLong = System.currentTimeMillis()
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER)
            HandleAcceleration(event, tsLong)
        if (event?.sensor?.type == Sensor.TYPE_STEP_COUNTER)
            HadleSteps(event, tsLong)
        if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE)
            HandleGyroscope(event, tsLong)

    }

    private fun HandleGyroscope(event: SensorEvent, tsLong: Long) {
        val gyro1 = event.values
        if (abs(gyro1[0]) > abs(maxesGyro[0]))
            maxesGyro[0] = gyro1[0]
        if (abs(gyro1[1]) > abs(maxesGyro[1]))
            maxesGyro[1] = gyro1[1]
        if (abs(gyro1[2]) > abs(maxesGyro[2]))
            maxesGyro[2] = gyro1[2]

        textMaxesGyro.text = "${maxesGyro[0]},${maxesGyro[1]},${maxesGyro[2]}";
        textGyro.text = "${gyro1[0]},${gyro1[1]},${gyro1[2]}"

        eventDataBatch.tryAdd(EventData(Gson().toJson(object {
            val type = DataType.Gyroscope
            val values = event.values
            val timestamp = tsLong
        })))
    }

    private fun HadleSteps(event: SensorEvent, tsLong: Long) {
        totalSteps = event!!.values[0]
        val currentSteps = totalSteps.toInt() - previousTotalSteps.toInt()
        textSteps.text = ("$currentSteps")

        eventDataBatch.tryAdd(EventData(Gson().toJson(object {
            val type = DataType.Steps
            val values = event.values
            val timestamp = tsLong
        })))
    }

    private fun HandleAcceleration(event: SensorEvent, tsLong: Long) {
        val acc1 = event.values
        if (abs(acc1[0]) > abs(maxes[0]))
            maxes[0] = acc1[0]
        if (abs(acc1[1]) > abs(maxes[1]))
            maxes[1] = acc1[1]
        if (abs(acc1[2]) > abs(maxes[2]))
            maxes[2] = acc1[2]

        textMaxesAcce.text = "${maxes[0]},${maxes[1]},${maxes[2]}";
        textAcce.text = "${acc1[0]},${acc1[1]},${acc1[2]}"

        eventDataBatch.tryAdd(EventData(Gson().toJson(object {
            val type = DataType.Accelometer
            val values = event.values
            val timestamp = tsLong
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
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    fun resetSteps() {
        var tv_stepsTaken = findViewById<TextView>(R.id.tv_stepsTaken)
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