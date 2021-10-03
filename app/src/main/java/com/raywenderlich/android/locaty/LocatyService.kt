/**
 * Copyright (c) 2020 Razeware LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.raywenderlich.android.locaty

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlin.math.round

class LocatyService : Service(), SensorEventListener {

    private var background = false
    private val notificationActivityRequestCode = 0
    private val notificationId = 1
    private val notificationStopRequestCode = 2


    private lateinit var sensorManager: SensorManager
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(9)

    companion object {
        val KEY_ANGLE = "angle"
        val KEY_DIRECTION = "direction"
        val KEY_BACKGROUND = "background"
        val KEY_NOTIFICATION_ID = "notificationId"
        val KEY_ON_SENSOR_CHANGED_ACTION = "cn.djzhao.compass.NO_SENSOR_CHANGED"
        val KEY_NOTIFICATION_STOP_ACTION = "cn.djzhao.compass.NOTIFICATION_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer ->
            sensorManager.registerListener(
                this, accelerometer,
                SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI
            )
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { magneticField ->
            sensorManager.registerListener(
                this, magneticField,
                SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI
            )
        }
        val notification = createNotification(getString(R.string.not_available), 0.0)
        startForeground(notificationId, notification)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            background = it.getBooleanExtra(KEY_BACKGROUND, false)
        }
        return START_STICKY
    }

    private fun createNotification(direction: String, angle: Double): Notification {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
            val notificationChannel = NotificationChannel(
                application.packageName,
                "指南针通知",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                enableLights(false)
                setSound(null, null)
                enableVibration(false)
                vibrationPattern = longArrayOf(0L)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(notificationChannel)
        }

        val notificationBuilder =
            NotificationCompat.Builder(applicationContext, application.packageName)

        val contentIntent = PendingIntent.getActivity(
            this, notificationActivityRequestCode,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopNotificationIntent = PendingIntent.getBroadcast(
            this,
            notificationStopRequestCode,
            Intent(this, ActionListener::class.java).apply {
                action = KEY_NOTIFICATION_STOP_ACTION
                putExtra(KEY_NOTIFICATION_ID, notificationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        notificationBuilder
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .setContentTitle(resources.getString(R.string.app_name))
            .setContentText("当前朝向：$direction, 角度：$angle")
            .setWhen(System.currentTimeMillis())
            .setDefaults(0)
            .setVibrate(longArrayOf(0L))
            .setSound(null)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentIntent(contentIntent)
            .addAction(
                R.mipmap.ic_launcher_round,
                getString(R.string.stop_notifications),
                stopNotificationIntent
            )

        return notificationBuilder.build()
    }

    class ActionListener : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null && intent.action != null) {
                if (intent.action == KEY_NOTIFICATION_STOP_ACTION) {
                    context?.let {
                        val locatyIntent = Intent(context, LocatyService::class.java)
                        context.stopService(locatyIntent)
                        val notificationId = intent.getIntExtra(KEY_NOTIFICATION_ID, -1)
                        if (notificationId != -1) {
                            val notificationManager =
                                it.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            notificationManager.cancel(notificationId)
                        }
                    }
                }
            }
        }

    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) {
            return
        }
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.size)
        }
        updateOrientationAngles()
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
        // no need
    }

    private fun updateOrientationAngles() {
        SensorManager.getRotationMatrix(
            rotationMatrix,
            null,
            accelerometerReading,
            magnetometerReading
        )
        val orientation = SensorManager.getOrientation(rotationMatrix, orientationAngles)
        val degrees = (Math.toDegrees(orientation[0].toDouble()) + 360) % 360.0
        val angle = round(degrees * 100) / 100
        val direction = getDirection(degrees)
        val intent = Intent().apply {
            putExtra(KEY_ANGLE, angle)
            putExtra(KEY_DIRECTION, direction)
            action = KEY_ON_SENSOR_CHANGED_ACTION
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
        if (background) {
            val notification = createNotification(direction, angle)
            startForeground(notificationId, notification)
        } else {
            stopForeground(true)
        }
    }

    private fun getDirection(angle: Double): String {
        var direction = ""

        if (angle >= 350 || angle <= 10)
            direction = "北"
        if (angle < 350 && angle > 280)
            direction = "西北"
        if (angle <= 280 && angle > 260)
            direction = "西"
        if (angle <= 260 && angle > 190)
            direction = "西南"
        if (angle <= 190 && angle > 170)
            direction = "南"
        if (angle <= 170 && angle > 100)
            direction = "东南"
        if (angle <= 100 && angle > 80)
            direction = "东"
        if (angle <= 80 && angle > 10)
            direction = "东北"

        return direction
    }

}