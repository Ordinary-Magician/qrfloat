package com.qrfloat.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 房间界面（角色分离）：
 *  - role=send（发送端）：自动创建房间（随机空闲号），显示房间号/人数，可去相机扫描发送
 *  - role=receive（接收端）：输入房间号加入，显示人数与收到的二维码
 */
class RoomActivity : AppCompatActivity(), RelayClient.Listener {

    private lateinit var tvStatus: TextView
    private lateinit var tvRoomCode: TextView
    private lateinit var tvMembers: TextView
    private lateinit var sendSection: View
    private lateinit var joinSection: View
    private lateinit var receiveSection: View
    private lateinit var etRoom: EditText
    private lateinit var tvReceived: TextView
    private lateinit var imgQr: ImageView

    private var isSend = true
    private var pendingAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_room)
        isSend = intent.getStringExtra("role") != "receive"

        tvStatus = findViewById(R.id.tvRoomStatus)
        tvRoomCode = findViewById(R.id.tvRoomCode)
        tvMembers = findViewById(R.id.tvMembers)
        sendSection = findViewById(R.id.sendSection)
        joinSection = findViewById(R.id.joinSection)
        receiveSection = findViewById(R.id.receiveSection)
        etRoom = findViewById(R.id.etRoom)
        tvReceived = findViewById(R.id.tvReceived)
        imgQr = findViewById(R.id.imgQr)
        val btnJoin = findViewById<Button>(R.id.btnJoin)
        val btnScan = findViewById<Button>(R.id.btnScan)
        val btnTest = findViewById<Button>(R.id.btnTest)
        val btnLeave = findViewById<Button>(R.id.btnLeave)

        App.relay.listener = this

        if (isSend) {
            sendSection.visibility = View.VISIBLE
            joinSection.visibility = View.GONE
            receiveSection.visibility = View.GONE
            tvStatus.text = "正在创建房间…"
            ensureConnectedThen { App.relay.createRoom() }
        } else {
            sendSection.visibility = View.GONE
            joinSection.visibility = View.VISIBLE
            receiveSection.visibility = View.GONE
            tvStatus.text = "输入房间号加入（接收端）"
            ensureConnectedThen { }
        }

        btnJoin.setOnClickListener {
            val code = etRoom.text.toString().trim()
            if (!code.matches(Regex("\\d{6}"))) {
                Toast.makeText(this, "请输入 6 位数字房间号", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvStatus.text = "正在加入…"
            ensureConnectedThen { App.relay.joinRoom(code) }
        }

        btnScan.setOnClickListener { startActivity(Intent(this, ScanActivity::class.java)) }

        btnTest.setOnClickListener {
            App.relay.sendTest()
            Toast.makeText(this, "已发送测试消息，看接收端是否弹出", Toast.LENGTH_SHORT).show()
        }

        btnLeave.setOnClickListener {
            App.relay.leaveRoom()
            finish()
        }
    }

    private fun ensureConnectedThen(action: () -> Unit) {
        if (App.relay.connected) action()
        else { pendingAction = action; App.relay.connect() }
    }

    override fun onResume() {
        super.onResume()
        App.relay.listener = this
        updateMembers()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            if (App.relay.listener === this) App.relay.listener = null
            App.relay.leaveRoom()
        }
    }

    private fun updateMembers() {
        val n = App.relay.memberCount
        tvMembers.text = if (isSend) "房间人数：$n" else {
            if (App.relay.ownerPresent) "房间人数：$n · 发送端在线" else "房间人数：$n · 发送端离线"
        }
    }

    // ---- RelayClient.Listener ----

    override fun onConnected() {
        val a = pendingAction; pendingAction = null
        a?.invoke()
    }

    override fun onDisconnected() {
        tvStatus.text = "连接已断开，正在重连…"
    }

    override fun onRoomCreated(room: String) {
        tvRoomCode.text = room
        tvStatus.text = "房间已创建（你是发送端）"
    }

    override fun onJoined(room: String) {
        tvRoomCode.text = room
        joinSection.visibility = View.GONE
        receiveSection.visibility = View.VISIBLE
        tvStatus.text = "已加入房间 $room（你是接收端）"
    }

    override fun onJoinFailed(reason: String) {
        tvStatus.text = reason
    }

    override fun onMembersChanged(count: Int, ownerPresent: Boolean) {
        updateMembers()
    }

    override fun onTextReceived(text: String) {
        tvReceived.text = text
        imgQr.setImageBitmap(QrCodeGen.generate(text, 400))
    }

    override fun onError(msg: String) {
        tvStatus.text = msg
    }
}
