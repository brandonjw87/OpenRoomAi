package com.openroomai.app.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Which way is the back camera pointing? Uses the fused rotation-vector sensor, corrected from
 * magnetic to true north when a location is known. Bearings are degrees clockwise from north.
 */
class Compass(private val context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val r = FloatArray(9)
    private var sx = 0.0
    private var sy = 0.0
    private var declination = 0f

    val available get() = sensor != null

    /** Smoothed bearing of the camera's viewing direction, or null while the phone lies flat. */
    @Volatile var cameraBearing: Float? = null
        private set
    @Volatile var accuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE
        private set

    fun start() {
        declination = Places.lastKnown(context)?.let {
            GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination
        } ?: 0f
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, e.values)
        // Row-major device→world (East, North, Up). The back camera looks along device −Z.
        val east = -r[2]
        val north = -r[5]
        if (hypot(east, north) < 0.35f) { cameraBearing = null; return }
        val b = Math.toDegrees(atan2(east, north).toDouble()) + declination
        // Circular low-pass so the needle doesn't jitter.
        val rad = Math.toRadians(b)
        sx = sx * 0.85 + sin(rad) * 0.15
        sy = sy * 0.85 + cos(rad) * 0.15
        cameraBearing = ((Math.toDegrees(atan2(sx, sy)) + 360.0) % 360.0).toFloat()
    }

    override fun onAccuracyChanged(s: Sensor?, acc: Int) { accuracy = acc }
}

object Places {
    fun hasPermission(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Best cached fix without waking the GPS; only latitude matters for the sun. */
    @Suppress("MissingPermission")
    fun lastKnown(c: Context): Location? {
        if (!hasPermission(c)) return null
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }
}
