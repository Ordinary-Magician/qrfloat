package com.qrfloat.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.Locale
import kotlin.random.Random

/**
 * 无服务器房间中转（MQTT 公共 broker）：
 *  - 房间号 = Topic 前缀 qrfloat/<号>/
 *  - members/<自己id>  保留消息，心跳 {ts, role}，用来数人数、判断房主、判断房间是否存在
 *  - msg               普通消息 {type:data, from, text}
 *  - 一源：只有房主(role=owner)发 data，接收端只接受 from==房主 的 data
 *  - 创建房间自动挑随机空闲号（占用就换），永不失败
 *  - 加入房间：有新鲜 owner 心跳才成功，否则失败
 *  - 断线自动重连，重连后重新订阅+重发心跳
 */
class RelayClient : MqttCallback {

    enum class Role { NONE, OWNER, RECEIVER }

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onRoomCreated(room: String)
        fun onJoined(room: String)
        fun onJoinFailed(reason: String)
        fun onMembersChanged(count: Int, ownerPresent: Boolean)
        fun onTextReceived(text: String)
        fun onError(msg: String)
    }

    companion object {
        private const val MEMBER_TTL = 25_000L   // 成员心跳超过 25s 视为离线
        private const val HEARTBEAT_MS = 10_000L  // 每 10s 发一次成员心跳
        private const val TICK_MS = 1_000L        // 每秒清理/刷新一次
        private const val CHECK_WAIT = 1_200L     // 创建/加入时等保留消息的时间
    }

    var listener: Listener? = null

    var role: Role = Role.NONE
        private set
    var currentRoom: String? = null
        private set
    var ownerId: String? = null
        private set
    var connected = false
        private set

    private data class Member(val ts: Long, val role: String)
    private val members = HashMap<String, Member>()

    private val TAG = "RelayClient"
    private val clientId = "qrfloat-" + randomString(10)
    private val handler = Handler(Looper.getMainLooper())
    private var client: MqttAsyncClient? = null
    private var connecting = false
    private var reconnectScheduled = false
    private var tickStarted = false
    private var tickCounter = 0
    private var lastNotifiedCount = -1
    private var lastNotifiedOwner = false

    val memberCount: Int get() = members.size
    val isOwner: Boolean get() = role == Role.OWNER
    val ownerPresent: Boolean
        get() = members.values.any { it.role == "owner" && System.currentTimeMillis() - it.ts < MEMBER_TTL }

    // ---------------- 连接 ----------------

    fun connect() {
        if (connected || connecting) return
        val broker = Prefs.getBroker()
        if (broker.isBlank()) { handler.post { listener?.onError("请先设置 broker 地址") }; return }
        connecting = true
        try {
            val c = MqttAsyncClient(broker, clientId, MemoryPersistence())
            client = c
            c.setCallback(this)
            val opts = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 15
                keepAliveInterval = 30
            }
            c.connect(opts, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    connecting = false
                    connected = true
                    Log.d(TAG, "broker 连接成功")
                    handler.post {
                        listener?.onConnected()
                        // 重连：重新订阅房间并重发自己的心跳
                        currentRoom?.let { code ->
                            subscribeAll(code)
                            rePublishSelf(code)
                            startTick()
                        }
                    }
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    connecting = false
                    connected = false
                    Log.w(TAG, "broker 连接失败: ${exception?.message}")
                    handler.post { listener?.onError(exception?.message ?: "连接 broker 失败") }
                    scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            connecting = false
            Log.w(TAG, "broker 连接异常: ${e.message}")
            handler.post { listener?.onError(e.message ?: "连接 broker 失败") }
            scheduleReconnect()
        }
    }

    // ---------------- 创建房间（房主/发送端） ----------------

    fun createRoom() {
        role = Role.OWNER
        tryCreate(0)
    }

    private fun tryCreate(attempt: Int) {
        if (attempt > 15) {
            role = Role.NONE; currentRoom = null
            handler.post { listener?.onError("创建房间失败，请重试") }
            return
        }
        val code = String.format(Locale.US, "%06d", Random.nextInt(1000000))
        members.clear(); ownerId = null
        currentRoom = code
        role = Role.OWNER
        subscribeAll(code)
        handler.postDelayed({
            if (currentRoom != code) return@postDelayed
            if (ownerPresent) {
                // 被占用 → 换号重试
                unsubscribeAll(code)
                currentRoom = null
                handler.postDelayed({ tryCreate(attempt + 1) }, 150)
            } else {
                // 空闲 → 成为房主
                ownerId = clientId
                publishMember(code, "owner")
                startTick()
                lastNotifiedCount = -1
                notifyMembers(force = true)
                listener?.onRoomCreated(code)
            }
        }, CHECK_WAIT)
    }

    // ---------------- 加入房间（接收端） ----------------

    fun joinRoom(code: String) {
        role = Role.RECEIVER
        members.clear(); ownerId = null
        currentRoom = code
        subscribeAll(code)
        handler.postDelayed({
            if (currentRoom != code) return@postDelayed
            if (ownerPresent) {
                publishMember(code, "recv")
                startTick()
                lastNotifiedCount = -1
                notifyMembers(force = true)
                listener?.onJoined(code)
            } else {
                unsubscribeAll(code)
                currentRoom = null
                role = Role.NONE
                listener?.onJoinFailed("房间不存在或已失效")
            }
        }, CHECK_WAIT)
    }

    // ---------------- 离开 ----------------

    fun leaveRoom() {
        val code = currentRoom
        if (code != null) {
            // 清除自己的保留心跳
            publishRetained("qrfloat/$code/members/$clientId", "")
            unsubscribeAll(code)
        }
        stopTick()
        members.clear()
        ownerId = null
        currentRoom = null
        role = Role.NONE
        lastNotifiedCount = -1
    }

    // ---------------- 发送数据（仅房主） ----------------

    fun sendText(text: String) {
        if (role != Role.OWNER) return
        val code = currentRoom ?: return
        publish(code, JSONObject().put("type", "data").put("from", clientId).put("text", text).toString())
    }

    fun sendTest() {
        if (role != Role.OWNER) return
        sendText("【测试消息】连接正常 ✓ " + System.currentTimeMillis())
    }

    // ---------------- MQTT 基础 ----------------

    private fun subscribeAll(code: String) {
        try {
            client?.subscribe("qrfloat/$code/#", 1)
        } catch (e: Exception) { Log.w(TAG, "订阅异常: ${e.message}") }
    }

    private fun unsubscribeAll(code: String) {
        try { client?.unsubscribe("qrfloat/$code/#") } catch (e: Exception) {}
    }

    private fun publish(code: String, payload: String) {
        try { client?.publish("qrfloat/$code/msg", payload.toByteArray(), 1, false) }
        catch (e: Exception) { Log.w(TAG, "发送异常: ${e.message}") }
    }

    private fun publishMember(code: String, memberRole: String) {
        val obj = JSONObject().put("ts", System.currentTimeMillis()).put("role", memberRole)
        publishRetained("qrfloat/$code/members/$clientId", obj.toString())
    }

    private fun publishRetained(topic: String, payload: String) {
        try { client?.publish(topic, payload.toByteArray(), 1, true) }
        catch (e: Exception) { Log.w(TAG, "发布保留异常: ${e.message}") }
    }

    private fun rePublishSelf(code: String) {
        val r = if (role == Role.OWNER) "owner" else "recv"
        publishMember(code, r)
    }

    // ---------------- 心跳 / 人数 tick ----------------

    private fun startTick() {
        if (tickStarted) return
        tickStarted = true
        tickCounter = 0
        handler.post(tick)
    }

    private fun stopTick() {
        tickStarted = false
        handler.removeCallbacks(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!tickStarted) return
            tickCounter++
            // 清理超时成员
            val now = System.currentTimeMillis()
            val before = members.size
            val iter = members.entries.iterator()
            while (iter.hasNext()) {
                val e = iter.next()
                if (now - e.value.ts > MEMBER_TTL) iter.remove()
            }
            if (members.size != before) {
                if (ownerId != null && !members.containsKey(ownerId)) ownerId = null
                notifyMembers()
            }
            // 每 10 秒重发自己的心跳
            if (tickCounter % 10 == 0) {
                currentRoom?.let { code -> rePublishSelf(code) }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun notifyMembers(force: Boolean = false) {
        val c = members.size
        val op = ownerPresent
        if (force || c != lastNotifiedCount || op != lastNotifiedOwner) {
            lastNotifiedCount = c
            lastNotifiedOwner = op
            listener?.onMembersChanged(c, op)
        }
    }

    // ---------------- MqttCallback ----------------

    override fun connectionLost(cause: Throwable) {
        Log.w(TAG, "连接丢失: ${cause.message}")
        connected = false; connecting = false
        handler.post { listener?.onDisconnected() }
        scheduleReconnect()
    }

    override fun messageArrived(topic: String, message: MqttMessage) {
        val code = currentRoom ?: return
        val prefix = "qrfloat/$code/"
        if (!topic.startsWith(prefix)) return
        val sub = topic.substring(prefix.length)
        val payload = String(message.payload)
        if (sub.startsWith("members/")) {
            val id = sub.removePrefix("members/")
            if (id == clientId) return // 忽略自己
            if (payload.isEmpty()) {
                members.remove(id); notifyMembers(); return
            }
            try {
                val obj = JSONObject(payload)
                val ts = obj.optLong("ts", System.currentTimeMillis())
                val mRole = obj.optString("role", "recv")
                if (mRole == "owner") ownerId = id
                members[id] = Member(ts, mRole)
                notifyMembers()
            } catch (e: Exception) {}
        } else if (sub == "msg") {
            try {
                val obj = JSONObject(payload)
                if (obj.optString("from") == clientId) return
                if (obj.optString("type") == "data") {
                    // 一源：接收端只接受房主的数据
                    if (role == Role.RECEIVER && obj.optString("from") != ownerId) return
                    handler.post { listener?.onTextReceived(obj.optString("text")) }
                }
            } catch (e: Exception) {}
        }
    }

    override fun deliveryComplete(token: IMqttDeliveryToken) {}

    private fun scheduleReconnect() {
        if (reconnectScheduled) return
        if (currentRoom == null) return
        reconnectScheduled = true
        handler.postDelayed({
            reconnectScheduled = false
            if (!connected && currentRoom != null) connect()
        }, 3000)
    }

    private fun randomString(len: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..len).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }
}
