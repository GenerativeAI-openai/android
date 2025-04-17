package com.junu.screenstream

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.android.volley.Request
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private lateinit var virtualDisplay: VirtualDisplay
    private lateinit var imageReader: ImageReader
    private val width = 720
    private val height = 1280
    private val dpi = 320
    private lateinit var surface: Surface

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        surface = imageReader.surface
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ScreenCapture",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )

        startCaptureLoop()

        return START_STICKY
    }

    private fun startCaptureLoop() {
        val handler = Handler(Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                val image = imageReader.acquireLatestImage() ?: run {
                    handler.postDelayed(this, 100)
                    return
                }

                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                sendToServer(bitmapToBase64(croppedBitmap))

                handler.postDelayed(this, 100)
            }
        })
    }

    private fun sendToServer(base64Image: String) {
        val json = JSONObject()
        json.put("image", base64Image)

        val request = JsonObjectRequest(
            Request.Method.POST,
            "http://<PC_IP>:5000/upload", // <-- 여기를 본인 PC IP로 바꿔줘
            json,
            {},
            { error -> Log.e("UPLOAD", "Error: $error") }
        )
        Volley.newRequestQueue(this).add(request)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, output)
        val bytes = output.toByteArray()
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun createNotification(): Notification {
        val channelId = "screen_stream"
        val channel = NotificationChannel(channelId, "화면 스트리밍", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("화면 스트리밍 중")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    override fun onBind(intent: Intent?) = null
}
