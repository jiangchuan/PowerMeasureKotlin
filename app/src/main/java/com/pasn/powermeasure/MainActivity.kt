package com.pasn.powermeasure

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.AsyncTask
import android.os.Bundle
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.android.synthetic.main.activity_main.*
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), View.OnClickListener, SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        grpc_response_text!!.movementMethod = ScrollingMovementMethod()
        send_button!!.setOnClickListener(this)
    }

    override fun onResume() {
        super.onResume()

        // Get updates from the accelerometer and magnetometer at a constant rate.
        // To make batch operations more efficient and reduce power consumption,
        // provide support for delaying updates to the application.
        //
        // In this example, the sensor reporting delay is small enough such that
        // the application receives an update before the system checks the sensor
        // readings again.
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer ->
            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_NORMAL,
                SensorManager.SENSOR_DELAY_UI
            )
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { magneticField ->
            sensorManager.registerListener(
                this,
                magneticField,
                SensorManager.SENSOR_DELAY_NORMAL,
                SensorManager.SENSOR_DELAY_UI
            )
        }
    }

    override fun onPause() {
        super.onPause()
        // Don't receive any more updates from either sensor.
        sensorManager.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Do something here if sensor accuracy changes.
        // You must implement this callback in your code.
    }

    // Get readings from accelerometer and magnetometer. To simplify calculations,
    // consider storing these readings as unit vectors.
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
        }
    }

    // Compute the three orientation angles based on the most recent readings from
    // the device's accelerometer and magnetometer.
    fun updateOrientationAngles() {
        // Update rotation matrix, which is needed to update orientation angles.
        SensorManager.getRotationMatrix(
            rotationMatrix,
            null,
            accelerometerReading,
            magnetometerReading
        )
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
    }

    override fun onClick(view: View) {
        updateOrientationAngles()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(host_edit_text!!.windowToken, 0)
        send_button!!.isEnabled = false
        grpc_response_text!!.text = ""
        GrpcTask(this)
            .execute(
                host_edit_text!!.text.toString(),
//                message_edit_text!!.text.toString(),
                orientationAngles.contentToString(),
                port_edit_text!!.text.toString()
            )
    }

    private class GrpcTask constructor(activity: Activity) : AsyncTask<String, Void, String>() {
        private val activityReference: WeakReference<Activity> = WeakReference(activity)
        private var channel: ManagedChannel? = null

        override fun doInBackground(vararg params: String): String {
            val host = params[0]
            val message = params[1]
            val portStr = params[2]
            val port = if (TextUtils.isEmpty(portStr)) 0 else Integer.valueOf(portStr)
            return try {
                channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build()
                val stub = GreeterGrpc.newBlockingStub(channel)
                val request = HelloRequest.newBuilder().setName(message).build()
                val reply = stub.sayHello(request)
                reply.message
            } catch (e: Exception) {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                e.printStackTrace(pw)
                pw.flush()
                "Failed... : %s".format(sw)
            }
        }

        override fun onPostExecute(result: String) {
            try {
                channel?.shutdown()?.awaitTermination(1, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val activity = activityReference.get() ?: return
            val resultText: TextView = activity.findViewById(R.id.grpc_response_text)
            val sendButton: Button = activity.findViewById(R.id.send_button)
            resultText.text = result
            sendButton.isEnabled = true
        }
    }
}
