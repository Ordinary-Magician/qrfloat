package com.qrfloat.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 发送端：连续相机扫描，二维码内容一变就发送（防抖 400ms），底部实时显示当前内容与接收端数。
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvCurrent: TextView
    private lateinit var tvStatus: TextView

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
    )
    private lateinit var executor: ExecutorService
    private var lastSent: String? = null
    private var sendCount = 0
    private var candidate = ""
    private var candidateSince = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val memberTicker = object : Runnable {
        override fun run() { updateStatus(); handler.postDelayed(this, 1000) }
    }

    private val reqCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera()
        else { Toast.makeText(this, "需要相机权限", Toast.LENGTH_LONG).show(); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        previewView = findViewById(R.id.previewView)
        tvCurrent = findViewById(R.id.tvCurrent)
        tvStatus = findViewById(R.id.tvStatus)
        val btnStop = findViewById<Button>(R.id.btnStop)
        btnStop.setOnClickListener { finish() }

        updateStatus()
        handler.post(memberTicker)

        executor = Executors.newSingleThreadExecutor()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            reqCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun updateStatus() {
        val receivers = (App.relay.memberCount - 1).coerceAtLeast(0)
        tvStatus.text = "发送端 · 接收端在线 $receivers 个 · 已发 $sendCount 次"
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(1280, 720))
                .build()
            analysis.setAnalyzer(executor) { proxy -> analyze(proxy) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null) { proxy.close(); return }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        val imgW = image.width
        val imgH = image.height
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                var pick: String? = null
                for (b in barcodes) {
                    if (b.format != Barcode.FORMAT_QR_CODE) continue
                    val v = b.rawValue ?: continue
                    val bb = b.boundingBox
                    if (bb != null && (bb.width() < imgW * 0.08 || bb.height() < imgH * 0.08)) continue
                    pick = v
                    break
                }
                val v = pick ?: return@addOnSuccessListener

                tvCurrent.text = "当前：$v"

                // 防抖：同一个值连续稳定 >= 400ms 才发送
                val now = SystemClock.elapsedRealtime()
                if (v == candidate) {
                    if (now - candidateSince >= 400 && v != lastSent) {
                        lastSent = v
                        if (App.relay.isOwner) {
                            App.relay.sendText(v)
                            sendCount++
                            updateStatus()
                        }
                    }
                } else {
                    candidate = v
                    candidateSince = now
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(memberTicker)
        executor.shutdown()
        scanner.close()
    }
}
