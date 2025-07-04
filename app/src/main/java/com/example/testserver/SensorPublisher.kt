package com.example.testserver

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.thingweb.thing.schema.InteractionInput
import org.eclipse.thingweb.thing.schema.WoTExposedThing
import java.util.concurrent.CountDownLatch

class SensorPublisher(
    private val context: Context,
    private val thing: WoTExposedThing,
    private val enabledSensors: List<Sensor>
) {
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isPublishing = false
    private val objectMapper = ObjectMapper()

    fun startPublishing() {
        if (isPublishing) return
        isPublishing = true

        coroutineScope.launch {
            while (isPublishing) {
                try {
                    publishSensorValues()
                    delay(500)
                } catch (e: Exception) {
                    Log.e("SENSOR_PUBLISHER", "Errore durante pubblicazione: ", e)
                    delay(1000)
                }
            }
        }
    }

    fun stopPublishing() {
        isPublishing = false
        coroutineScope.cancel()
    }

    private suspend fun publishSensorValues() {
        for (sensor in enabledSensors) {
            try {
                val type = sensor.type
                val name = sensor.name
                val sensorValuesCount = getSensorValuesCount(type)
                val values = readSensorValues(context, type)

                if (values.isNotEmpty()) {
                    if (sensorValuesCount == 1) {
                        val propName = sanitizeSensorName(name, type)
                        val jsonValue = objectMapper.valueToTree<JsonNode>(values[0])
                        val interactionInput = InteractionInput.Value(jsonValue)
                        thing.emitPropertyChange(propName, interactionInput)
                        Log.d("SENSOR_PUBLISHER", "Pubblicato $propName: ${values[0]}")
                    } else {
                        for (i in 0 until minOf(sensorValuesCount, values.size)) {
                            val suffix = listOf("x", "y", "z", "w", "v").getOrNull(i)
                            val propName = "${sanitizeSensorName(name, type)}_$suffix"
                            val jsonValue = objectMapper.valueToTree<JsonNode>(values[i])
                            val interactionInput = InteractionInput.Value(jsonValue)
                            thing.emitPropertyChange(propName, interactionInput)
                            Log.d("SENSOR_PUBLISHER", "Pubblicato $propName: ${values[i]}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SENSOR_PUBLISHER", "Errore pubblicando sensore ${sensor.name}: ", e)
            }
        }
    }

    private fun getSensorValuesCount(sensorType: Int): Int = when (sensorType) {
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_GRAVITY,
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_MAGNETIC_FIELD -> 3

        Sensor.TYPE_ROTATION_VECTOR -> 4
        Sensor.TYPE_GAME_ROTATION_VECTOR -> 4
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> 5

        else -> 1
    }

    private fun sanitizeSensorName(name: String, type: Int): String {
        val sanitizedName = name.lowercase()
            .replace("\\s+".toRegex(), "-")
            .replace("[^a-z0-9\\-]".toRegex(), "")
        return "$sanitizedName-$type"
    }

    private fun readSensorValues(context: Context, sensorType: Int): FloatArray {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(sensorType) ?: return floatArrayOf()

        if (sensor == null) {
            Log.w("SENSOR_READ", "Sensore tipo $sensorType non disponibile")
            return floatArrayOf()
        }

        val latch = CountDownLatch(1)
        var values: FloatArray = floatArrayOf()
        var hasReceivedData = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event != null && event.sensor.type == sensorType && !hasReceivedData) {
                    hasReceivedData = true
                    values = event.values.copyOf()
                    Log.d("SENSOR_READ", "Ricevuti dati per sensore $sensorType: ${values.contentToString()}")
                    latch.countDown()
                    // sensorManager.unregisterListener(this)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        try {
            // Registra il listener con delay più veloce per lettura immediata
            val registered = sensorManager.registerListener(
                listener,
                sensor,
                SensorManager.SENSOR_DELAY_FASTEST
            )

            if (!registered) {
                Log.e("SENSOR_READ", "Impossibile registrare listener per sensore $sensorType")
                return floatArrayOf()
            }

            Log.d("SENSOR_READ", "Listener registrato per sensore $sensorType, attendo dati...")

            // Aspetta più a lungo per i dati del sensore
            val received = latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)

            if (!received) {
                Log.w("SENSOR_READ", "Timeout nella lettura del sensore $sensorType")
            }

        } catch (e: InterruptedException) {
            Log.e("SENSOR_READ", "Interruzione durante lettura sensore $sensorType", e)
            Thread.currentThread().interrupt()
        } finally {
            // Assicurati sempre di de-registrare il listener
            sensorManager.unregisterListener(listener)
            Log.d("SENSOR_READ", "Listener de-registrato per sensore $sensorType")
        }

        return values
    }
}