package com.qrfloat.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), RelayClient.Listener {

    private lateinit var etBroker: EditText
    private lateinit var tvStatus: TextView

    private val reqPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etBroker = findViewById(R.id.etBroker)
        tvStatus = findViewById(R.id.tvStatus)
        val btnSaveServer = findViewById<Button>(R.id.btnSaveServer)
        val btnSend = findViewById<Button>(R.id.btnSend)
        val btnReceive = findViewById<Button>(R.id.btnReceive)

        etBroker.setText(Prefs.getBroker())

        btnSaveServer.setOnClickListener {
            Prefs.setBroker(etBroker.text.toString())
            Toast.makeText(this, "已保存 Broker 地址", Toast.LENGTH_SHORT).show()
        }

        btnSend.setOnClickListener {
            startActivity(Intent(this, RoomActivity::class.java).putExtra("role", "send"))
        }
        btnReceive.setOnClickListener {
            startActivity(Intent(this, RoomActivity::class.java).putExtra("role", "receive"))
        }

        requestAllPermissions()
    }

    override fun onResume() {
        super.onResume()
        App.relay.listener = this
        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (App.relay.listener === this) App.relay.listener = null
    }

    private fun requestAllPermissions() {
        val need = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) reqPerms.launch(missing.toTypedArray())
    }

    private fun refreshStatus() {
        val r = App.relay
        tvStatus.text = when {
            r.currentRoom != null && r.role == RelayClient.Role.OWNER -> "发送端 · 房间 ${r.currentRoom} · 人数 ${r.memberCount}"
            r.currentRoom != null && r.role == RelayClient.Role.RECEIVER -> "接收端 · 房间 ${r.currentRoom} · 人数 ${r.memberCount}"
            r.connected -> "已连接 broker"
            else -> "未连接（进入房间时自动连接）"
        }
    }

    override fun onConnected() { refreshStatus() }
    override fun onDisconnected() { refreshStatus() }
    override fun onRoomCreated(room: String) { refreshStatus() }
    override fun onJoined(room: String) { refreshStatus() }
    override fun onJoinFailed(reason: String) {}
    override fun onMembersChanged(count: Int, ownerPresent: Boolean) { refreshStatus() }
    override fun onTextReceived(text: String) {}
    override fun onError(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}
