(function () {
  var $ = function (id) { return document.getElementById(id); };
  var myId = 'pc-' + Math.random().toString(36).slice(2, 12);
  var client = null, currentRoom = null, pending = null;
  var ownerId = null, joined = false, currentText = '';
  var members = {}; // id -> {ts, role}
  var MEMBER_TTL = 25000, HEARTBEAT_MS = 10000, CHECK_WAIT = 1200;

  // 初始化不生成二维码（不传 text），收到内容时才渲染
  var qr = new QRCode($('qrcode'), { width: 300, height: 300, correctLevel: QRCode.CorrectLevel.M });

  function setStatus(s, level) {
    $('statusText').textContent = s;
    $('dot').className = 'dot' + (level ? ' ' + level : '');
  }
  function base(code) { return 'qrfloat/' + code + '/'; }

  function connect(cb) {
    if (client && client.connected) { if (cb) cb(); return; }
    pending = cb;
    if (!client) {
      var broker = $('broker').value.trim();
      setStatus('正在连接 broker…', 'warn');
      client = mqtt.connect(broker, { clientId: myId, clean: true, reconnectPeriod: 2000, connectTimeout: 15000 });
      client.on('connect', function () {
        setStatus('已连接 broker', 'warn');
        if (pending) { var a = pending; pending = null; a(); }
        else if (currentRoom) { joinFlow(currentRoom); }
      });
      client.on('reconnect', function () { setStatus('重连中…', 'warn'); });
      client.on('error', function (e) { setStatus('连接错误：' + (e && e.message ? e.message : e), 'err'); });
      client.on('close', function () { setStatus('连接已断开', 'err'); });
      client.on('message', onMessage);
    } else {
      setStatus('正在连接…', 'warn');
    }
  }

  function joinFlow(code) {
    currentRoom = code;
    members = {}; ownerId = null; joined = false;
    $('members').textContent = '';
    // 加入/切换房间时清空上一次的二维码与内容，避免显示旧数据
    qr.clear();
    currentText = '';
    $('content').textContent = '等待接收…';
    $('meta').textContent = '';
    setStatus('正在检查房间 ' + code + '…', 'warn');
    client.subscribe(base(code) + '#', { qos: 1 }, function (err) {
      if (err) { setStatus('订阅失败：' + err.message, 'err'); return; }
      setTimeout(function () {
        if (freshOwnerExists()) {
          joined = true;
          publishMember(code, 'recv');
          setStatus('已加入房间 ' + code, 'ok');
          updateMembersUI();
        } else {
          currentRoom = null;
          setStatus('房间不存在或已失效（确认发送端已创建）', 'err');
        }
      }, CHECK_WAIT);
    });
  }

  function freshOwnerExists() {
    var now = Date.now();
    for (var id in members) {
      var m = members[id];
      if (m.role === 'owner' && now - m.ts < MEMBER_TTL) { ownerId = id; return true; }
    }
    return false;
  }

  function publishMember(code, role) {
    if (!client || !client.connected) return;
    var payload = JSON.stringify({ ts: Date.now(), role: role });
    client.publish(base(code) + 'members/' + myId, payload, { qos: 1, retain: true });
  }

  function onMessage(t, payload) {
    if (!currentRoom) return;
    var prefix = base(currentRoom);
    if (t.indexOf(prefix) !== 0) return;
    var sub = t.substring(prefix.length);
    var s = payload.toString();
    if (sub.indexOf('members/') === 0) {
      var id = sub.substring('members/'.length);
      if (id === myId) return;
      if (!s) { delete members[id]; updateMembersUI(); return; }
      try {
        var o = JSON.parse(s);
        if (o.role === 'owner') ownerId = id;
        members[id] = { ts: o.ts || Date.now(), role: o.role || 'recv' };
        updateMembersUI();
      } catch (e) {}
    } else if (sub === 'msg') {
      try {
        var m = JSON.parse(s);
        if (m.from === myId) return;
        if (m.type === 'data') {
          if (m.from !== ownerId) return; // 一源：只收房主
          showContent(m.text || '');
        }
      } catch (e) {}
    }
  }

  function updateMembersUI() {
    var now = Date.now();
    var count = 0, op = false;
    for (var id in members) {
      if (now - members[id].ts < MEMBER_TTL) {
        count++;
        if (members[id].role === 'owner') op = true;
      }
    }
    if (!currentRoom) { $('members').textContent = ''; return; }
    $('members').textContent = '房间人数：' + count + (joined ? (op ? ' · 发送端在线' : ' · 发送端离线') : '');
  }

  function showContent(text) {
    currentText = text;
    $('content').textContent = text || '（空内容）';
    $('meta').textContent = '更新于 ' + new Date().toLocaleTimeString();
    if (text) { qr.clear(); qr.makeCode(text); }
  }

  // 每秒清理过期成员
  setInterval(function () {
    var now = Date.now(), changed = false;
    for (var id in members) {
      if (now - members[id].ts >= MEMBER_TTL) { delete members[id]; changed = true; }
    }
    if (changed) updateMembersUI();
  }, 1000);

  // 每 10 秒发自己的心跳
  setInterval(function () {
    if (joined && currentRoom && client && client.connected) publishMember(currentRoom, 'recv');
  }, HEARTBEAT_MS);

  $('btnJoin').onclick = function () {
    var code = ($('room').value || '').trim();
    if (!/^\d{6}$/.test(code)) { alert('请输入 6 位数字房间号'); return; }
    connect(function () { joinFlow(code); });
  };

  $('btnCopy').onclick = function () {
    if (!currentText) { setStatus('还没有内容可复制', 'warn'); return; }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(currentText).then(function () {
        setStatus('已复制到剪贴板', 'ok');
      }).catch(function () { alert('内容：' + currentText); });
    } else { alert('内容：' + currentText); }
  };

  $('btnOpen').onclick = function () {
    if (!currentText) { setStatus('还没有内容', 'warn'); return; }
    if (/^(https?|weixin|wemeet|tencent|meeting):\/\//i.test(currentText.trim())) {
      window.open(currentText.trim(), '_blank');
    } else {
      setStatus('当前内容不是链接，无法直接打开', 'warn');
    }
  };

  connect();
})();
