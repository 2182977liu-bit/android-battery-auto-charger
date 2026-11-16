#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AutoPhoneZ 局域网网页查看端（测试用）

作用：不用 ESP32，先在一台局域网电脑上把手机上报的数据直观看到，
      用来判断「手机端软件是否正常工作」。

同一套协议：
    POST /battery     Header: X-Auth-Token     Body: JSON（与 ESP32 完全一致）
    GET  /status      与 ESP32 固件同样的调试接口
    GET  /            可视化面板（每秒自动刷新）
    GET  /api/state   面板用的 JSON
    POST /api/simulate  网页上的「模拟一条」按钮

用法：
    python3 tools/web_dashboard.py                  # 端口 8080，Token test-token-123456
    python3 tools/web_dashboard.py 9000 mytoken     # 指定端口和 Token

Windows 首次运行会弹防火墙提示，必须勾选「专用网络」并允许，否则手机连不上。
"""

import json
import socket
import sys
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
TOKEN = sys.argv[2] if len(sys.argv) > 2 else "test-token-123456"

LOW, HIGH = 30, 90
WARN_AFTER_SEC = 400     # 超过 6.5 分钟没数据 -> 黄色（心跳是 5 分钟）
BAD_AFTER_SEC = 900      # 超过 15 分钟没数据 -> 红色

LOCK = threading.Lock()

STATE = {
    "last_seen": 0.0,
    "last_event": "-",
    "level": -1,
    "charging": False,
    "plugged": "-",
    "device": "-",
    "model": "-",
    "android": "-",
    "temp": 0.0,
    "volt": 0,
    "phone_ts": 0,
    "count": 0,
    "low_hits": 0,
    "high_hits": 0,
    "plug_hits": 0,
    "relay": False,
    "events": [],     # 最近 60 条
    "history": [],    # {t, level} 用于画曲线
    "last_ip": "-",
}

EVENT_COLORS = {
    "LOW_BATTERY": "#f0a04b",
    "HIGH_BATTERY": "#3fb950",
    "PLUGGED": "#58a6ff",
    "UNPLUGGED": "#8b949e",
    "HELLO": "#39d2c0",
    "HEARTBEAT": "#6e7681",
    "TEST": "#d29922",
    "SIMULATED": "#d29922",
}


def now_str() -> str:
    return datetime.now().strftime("%H:%M:%S")


def local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def apply_thresholds(level: int) -> bool:
    """与 ESP32 固件一致的控制逻辑，这里只用来在面板上显示「假想继电器」状态。"""
    if level < 0:
        return STATE["relay"]
    if level <= LOW:
        return True
    if level >= HIGH:
        return False
    return STATE["relay"]


def record(data: dict, ip: str, simulated: bool = False) -> None:
    event = str(data.get("event", "UNKNOWN"))
    level = int(data.get("level", -1) or -1)
    charging = bool(data.get("charging", False))

    with LOCK:
        prev_relay = STATE["relay"]
        STATE["last_seen"] = time.time()
        STATE["last_event"] = event
        STATE["level"] = level
        STATE["charging"] = charging
        STATE["plugged"] = str(data.get("plugged", "-"))
        STATE["device"] = str(data.get("device_id", "-"))
        model = f"{data.get('manufacturer', '')} {data.get('model', '')}".strip()
        STATE["model"] = model or "-"
        STATE["android"] = str(data.get("android", "-"))
        STATE["temp"] = float(data.get("temp_c", 0) or 0)
        STATE["volt"] = int(data.get("voltage_mv", 0) or 0)
        STATE["phone_ts"] = int(data.get("ts", 0) or 0)
        STATE["last_ip"] = ip
        STATE["count"] += 1
        STATE["relay"] = apply_thresholds(level)

        if event == "LOW_BATTERY":
            STATE["low_hits"] += 1
        elif event == "HIGH_BATTERY":
            STATE["high_hits"] += 1
        elif event in ("PLUGGED", "UNPLUGGED"):
            STATE["plug_hits"] += 1

        STATE["events"].append({
            "t": now_str(),
            "event": event,
            "level": level,
            "charging": charging,
            "plugged": STATE["plugged"],
            "temp": STATE["temp"],
            "ip": ip,
        })
        if len(STATE["events"]) > 60:
            del STATE["events"][: len(STATE["events"]) - 60]

        hist = STATE["history"]
        t = time.time()
        if (not hist) or hist[-1]["level"] != level or (t - hist[-1]["t"]) > 60:
            hist.append({"t": t, "level": level})
            if len(hist) > 200:
                del hist[: len(hist) - 200]

        relay_changed = prev_relay != STATE["relay"]

    flag = " [模拟]" if simulated else ""
    print(f"[{now_str()}] {event:<12} level={level:>3}% chg={1 if charging else 0} "
          f"plug={STATE['plugged']:<9} {model or '-'}{flag}")
    if relay_changed:
        print(f"[{now_str()}] [继电器·模拟] {'ON  接通' if STATE['relay'] else 'OFF 断开'}")


def snapshot() -> dict:
    with LOCK:
        age = int(time.time() - STATE["last_seen"]) if STATE["last_seen"] else None
        return {
            "has_data": STATE["count"] > 0,
            "age_sec": age,
            "level": STATE["level"],
            "charging": STATE["charging"],
            "plugged": STATE["plugged"],
            "device": STATE["device"],
            "model": STATE["model"],
            "android": STATE["android"],
            "temp": STATE["temp"],
            "volt": STATE["volt"],
            "event": STATE["last_event"],
            "relay": STATE["relay"],
            "count": STATE["count"],
            "low_hits": STATE["low_hits"],
            "high_hits": STATE["high_hits"],
            "plug_hits": STATE["plug_hits"],
            "last_ip": STATE["last_ip"],
            "events": list(STATE["events"][-30:]),
            "history": list(STATE["history"][-60:]),
            "low": LOW,
            "high": HIGH,
        }


HTML = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>电量哨兵 · 局域网查看端</title>
<style>
  :root{
    --bg:#0d1117; --card:#161b22; --line:#30363d;
    --fg:#e6edf3; --dim:#8b949e;
    --ok:#3fb950; --warn:#f0a04b; --bad:#f85149; --cyan:#39d2c0; --blue:#58a6ff;
  }
  *{box-sizing:border-box;margin:0;padding:0}
  body{
    background:var(--bg);color:var(--fg);
    font-family:system-ui,-apple-system,"Segoe UI","Microsoft YaHei",sans-serif;
    padding:20px;min-height:100vh;
  }
  .wrap{max-width:1080px;margin:0 auto}
  header{display:flex;align-items:center;justify-content:space-between;
         margin-bottom:18px;flex-wrap:wrap;gap:10px}
  h1{font-size:20px;font-weight:600;letter-spacing:.5px}
  h1 span{color:var(--dim);font-size:13px;font-weight:400;margin-left:10px}
  .badge{padding:5px 12px;border-radius:999px;font-size:13px;
         border:1px solid var(--line);background:var(--card);color:var(--dim)}
  .badge.ok{color:var(--ok);border-color:rgba(63,185,80,.5)}
  .badge.warn{color:var(--warn);border-color:rgba(240,160,75,.5)}
  .badge.bad{color:var(--bad);border-color:rgba(248,81,73,.5)}

  .top{display:grid;grid-template-columns:300px 1fr;gap:16px}
  @media (max-width:760px){.top{grid-template-columns:1fr}}

  .ring-card{background:var(--card);border:1px solid var(--line);border-radius:14px;
             padding:18px;display:flex;align-items:center;justify-content:center;
             position:relative;min-height:300px}
  .ring{width:230px;height:230px}
  .ring-bg{fill:none;stroke:#21262d;stroke-width:16}
  .ring-fg{fill:none;stroke:var(--blue);stroke-width:16;
           stroke-linecap:round;transform:rotate(-90deg);
           transform-origin:100px 100px;
           transition:stroke-dashoffset .5s ease, stroke .3s}
  .ring-center{position:absolute;text-align:center;pointer-events:none}
  .level{font-size:54px;font-weight:700;line-height:1}
  .unit{font-size:14px;color:var(--dim);margin-top:2px}
  .chg{margin-top:10px;font-size:13px;padding:4px 12px;border-radius:999px;
       display:inline-block;border:1px solid var(--line);color:var(--dim)}
  .chg.on{color:var(--ok);border-color:rgba(63,185,80,.5)}

  .cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));
         gap:12px}
  .card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px}
  .k{font-size:12px;color:var(--dim);margin-bottom:6px}
  .v{font-size:15px;font-weight:600;word-break:break-all}
  .v.mono{font-family:ui-monospace,Consolas,monospace;font-size:13px;font-weight:400}

  .panel{background:var(--card);border:1px solid var(--line);border-radius:14px;
         padding:16px;margin-top:16px}
  .panel h2{font-size:14px;font-weight:600;margin-bottom:12px;color:var(--fg)}
  .panel h2 em{font-style:normal;color:var(--dim);font-weight:400;font-size:12px;margin-left:8px}
  canvas{width:100%;height:180px;display:block}

  table{width:100%;border-collapse:collapse;font-size:13px;
        font-family:ui-monospace,Consolas,monospace}
  th{color:var(--dim);font-weight:400;text-align:left;padding:6px 8px;
     border-bottom:1px solid var(--line);position:sticky;top:0;background:var(--card)}
  td{padding:6px 8px;border-bottom:1px solid #21262d;color:var(--fg)}
  .scroll{max-height:300px;overflow:auto}
  .ev{font-weight:600}

  footer{margin-top:18px;padding:14px 16px;background:var(--card);
         border:1px solid var(--line);border-radius:12px;font-size:13px;color:var(--dim);
         line-height:1.9}
  footer b{color:var(--cyan);font-weight:600;
           font-family:ui-monospace,Consolas,monospace}
  .row{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-top:10px}
  button{background:#21262d;color:var(--fg);border:1px solid var(--line);
         padding:8px 14px;border-radius:8px;font-size:13px;cursor:pointer}
  button:hover{border-color:var(--cyan);color:var(--cyan)}
  .tip{font-size:12px;color:var(--dim)}
</style>
</head>
<body>
<div class="wrap">
  <header>
    <h1>电量哨兵<span>局域网查看端 · 测试用</span></h1>
    <div id="badge" class="badge">等待手机上报</div>
  </header>

  <div class="top">
    <div class="ring-card">
      <svg class="ring" viewBox="0 0 200 200">
        <circle class="ring-bg" cx="100" cy="100" r="86"></circle>
        <circle id="arc" class="ring-fg" cx="100" cy="100" r="86"
                stroke-dasharray="540.35" stroke-dashoffset="540.35"></circle>
      </svg>
      <div class="ring-center">
        <div id="level" class="level">--</div>
        <div class="unit">%</div>
        <div id="chg" class="chg">未充电</div>
      </div>
    </div>

    <div class="cards">
      <div class="card"><div class="k">机型</div><div class="v" id="model">-</div></div>
      <div class="card"><div class="k">设备 ID</div><div class="v mono" id="device">-</div></div>
      <div class="card"><div class="k">Android</div><div class="v mono" id="android">-</div></div>
      <div class="card"><div class="k">供电方式</div><div class="v" id="plugged">-</div></div>
      <div class="card"><div class="k">温度</div><div class="v" id="temp">-</div></div>
      <div class="card"><div class="k">电压</div><div class="v" id="volt">-</div></div>
      <div class="card"><div class="k">最后事件</div><div class="v" id="event">-</div></div>
      <div class="card"><div class="k">最后上报</div><div class="v" id="age">-</div></div>
      <div class="card"><div class="k">累计报文</div><div class="v" id="count">0</div></div>
      <div class="card"><div class="k">低电量 / 高电量触发</div>
        <div class="v mono" id="hits">0 / 0</div></div>
      <div class="card"><div class="k">插拔触发</div><div class="v" id="plughits">0</div></div>
      <div class="card"><div class="k">继电器（模拟）</div><div class="v" id="relay">OFF</div></div>
    </div>
  </div>

  <div class="panel">
    <h2>电量曲线<em>仅显示变化点，最多 60 个</em></h2>
    <canvas id="chart"></canvas>
  </div>

  <div class="panel">
    <h2>事件流<em>最近 30 条</em></h2>
    <div class="scroll">
      <table>
        <thead><tr><th>时间</th><th>事件</th><th>电量</th><th>充电</th><th>温度</th><th>来源</th></tr></thead>
        <tbody id="events"></tbody>
      </table>
    </div>
  </div>

  <footer>
    手机 App 里这样填：IP <b>__IP__</b>　端口 <b>__PORT__</b>　Token <b>__TOKEN__</b>　设备 ID 随意<br>
    阈值：低电量 <b>__LOW__%</b> ／ 高电量 <b>__HIGH__%</b>（与 App 内保持一致）
    <div class="row">
      <button onclick="simulate()">模拟发送一条（不用手机也能验证面板）</button>
      <span class="tip">本页面只用于局域网测试，别映射到公网</span>
    </div>
  </footer>
</div>

<script>
const ARC_LEN = 540.35;
let lastEventCount = -1;

function colorFor(level){
  if (level < 0) return "#58a6ff";
  if (level <= 30) return "#f0a04b";
  if (level >= 90) return "#3fb950";
  return "#58a6ff";
}

function fmtAge(sec){
  if (sec === null) return "无数据";
  if (sec < 60) return sec + " 秒前";
  if (sec < 3600) return Math.floor(sec/60) + " 分钟前";
  return Math.floor(sec/3600) + " 小时前";
}

function render(s){
  const lv = s.level;
  document.getElementById("level").textContent = lv >= 0 ? lv : "--";

  const arc = document.getElementById("arc");
  const ratio = lv >= 0 ? Math.min(100, Math.max(0, lv)) / 100 : 0;
  arc.setAttribute("stroke-dashoffset", (ARC_LEN * (1 - ratio)).toFixed(2));
  arc.style.stroke = colorFor(lv);

  const chg = document.getElementById("chg");
  chg.textContent = s.charging ? "充电中 · " + s.plugged : "未充电";
  chg.className = "chg" + (s.charging ? " on" : "");

  document.getElementById("model").textContent = s.model;
  document.getElementById("device").textContent = s.device;
  document.getElementById("android").textContent = s.android;
  document.getElementById("plugged").textContent = s.plugged;
  document.getElementById("temp").textContent = s.temp ? s.temp + " °C" : "-";
  document.getElementById("volt").textContent = s.volt ? (s.volt/1000).toFixed(3) + " V" : "-";
  document.getElementById("event").textContent = s.event;
  document.getElementById("age").textContent = fmtAge(s.age_sec);
  document.getElementById("count").textContent = s.count;
  document.getElementById("hits").textContent = s.low_hits + " / " + s.high_hits;
  document.getElementById("plughits").textContent = s.plug_hits;

  const relay = document.getElementById("relay");
  relay.textContent = s.relay ? "ON（假想接通充电）" : "OFF（假想断开）";
  relay.style.color = s.relay ? "#3fb950" : "#8b949e";

  const badge = document.getElementById("badge");
  if (!s.has_data){
    badge.className = "badge"; badge.textContent = "等待手机上报";
  } else if (s.age_sec === null || s.age_sec > 900){
    badge.className = "badge bad"; badge.textContent = "离线 · " + fmtAge(s.age_sec);
  } else if (s.age_sec > 400){
    badge.className = "badge warn"; badge.textContent = "数据延迟 · " + fmtAge(s.age_sec);
  } else {
    badge.className = "badge ok"; badge.textContent = "在线 · " + fmtAge(s.age_sec);
  }

  if (s.events.length !== lastEventCount){
    lastEventCount = s.events.length;
    renderEvents(s.events);
  }
  lastHistory = s.history || [];
  lastLow = s.low;
  lastHigh = s.high;
  drawChart(lastHistory, lastLow, lastHigh);
}

function renderEvents(events){
  const colors = {
    LOW_BATTERY:"#f0a04b", HIGH_BATTERY:"#3fb950", PLUGGED:"#58a6ff",
    UNPLUGGED:"#8b949e", HELLO:"#39d2c0", HEARTBEAT:"#6e7681",
    TEST:"#d29922", SIMULATED:"#d29922"
  };
  const tbody = document.getElementById("events");
  const rows = events.slice().reverse().map(e => {
    const c = colors[e.event] || "#e6edf3";
    return `<tr>
      <td>${e.t}</td>
      <td class="ev" style="color:${c}">${e.event}</td>
      <td>${e.level >= 0 ? e.level + "%" : "-"}</td>
      <td>${e.charging ? "是" : "否"}</td>
      <td>${e.temp ? e.temp + "C" : "-"}</td>
      <td style="color:#8b949e">${e.ip}</td>
    </tr>`;
  }).join("");
  tbody.innerHTML = rows || `<tr><td colspan="6" style="color:#8b949e">还没有数据</td></tr>`;
}

function drawChart(history, low, high){
  const cv = document.getElementById("chart");
  const dpr = window.devicePixelRatio || 1;
  const w = cv.clientWidth, h = cv.clientHeight;
  cv.width = w * dpr; cv.height = h * dpr;
  const ctx = cv.getContext("2d");
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);

  const padL = 34, padR = 12, padT = 10, padB = 20;
  const plotW = w - padL - padR, plotH = h - padT - padB;

  const yOf = v => padT + plotH * (1 - v / 100);

  // 参考线
  ctx.strokeStyle = "#21262d"; ctx.lineWidth = 1;
  [0, 25, 50, 75, 100].forEach(v => {
    const y = yOf(v);
    ctx.beginPath(); ctx.moveTo(padL, y); ctx.lineTo(w - padR, y); ctx.stroke();
    ctx.fillStyle = "#6e7681"; ctx.font = "11px ui-monospace, monospace";
    ctx.fillText(v, 6, y + 4);
  });

  // 阈值线
  [[low, "#f0a04b"], [high, "#3fb950"]].forEach(([v, c]) => {
    const y = yOf(v);
    ctx.strokeStyle = c; ctx.globalAlpha = 0.45; ctx.setLineDash([5, 4]);
    ctx.beginPath(); ctx.moveTo(padL, y); ctx.lineTo(w - padR, y); ctx.stroke();
    ctx.setLineDash([]); ctx.globalAlpha = 1;
  });

  if (!history || history.length < 2) return;

  const t0 = history[0].t, t1 = history[history.length - 1].t;
  const span = Math.max(1, t1 - t0);
  const xOf = t => padL + plotW * ((t - t0) / span);

  ctx.strokeStyle = "#39d2c0"; ctx.lineWidth = 2;
  ctx.beginPath();
  history.forEach((p, i) => {
    const x = xOf(p.t), y = yOf(p.level);
    if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
  });
  ctx.stroke();

  const last = history[history.length - 1];
  ctx.fillStyle = colorFor(last.level);
  ctx.beginPath();
  ctx.arc(xOf(last.t), yOf(last.level), 3.5, 0, Math.PI * 2);
  ctx.fill();
}

async function tick(){
  try{
    const r = await fetch("/api/state", {cache:"no-store"});
    render(await r.json());
  }catch(e){
    const b = document.getElementById("badge");
    b.className = "badge bad"; b.textContent = "面板服务断开";
  }
}

async function simulate(){
  try{
    const r = await fetch("/api/simulate", {method:"POST"});
    if (!r.ok) throw new Error("HTTP " + r.status);
    await tick();
  }catch(e){
    alert("模拟失败：" + e.message);
  }
}

let lastHistory = [], lastLow = 30, lastHigh = 90;
tick();
setInterval(tick, 1000);
setInterval(() => drawChart(lastHistory, lastLow, lastHigh), 250);
</script>
</body>
</html>
"""


def render_html() -> bytes:
    html = (HTML
            .replace("__IP__", local_ip())
            .replace("__PORT__", str(PORT))
            .replace("__TOKEN__", TOKEN)
            .replace("__LOW__", str(LOW))
            .replace("__HIGH__", str(HIGH)))
    return html.encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    server_version = "AutoPhoneZWeb"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    # ---------- 工具 ----------
    def _send(self, code, body: bytes, ctype: str):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code, obj):
        self._send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"),
                   "application/json; charset=utf-8")

    # ---------- GET ----------
    def do_GET(self):
        path = urlparse(self.path).path
        if path in ("/", "/index.html"):
            self._send(200, render_html(), "text/html; charset=utf-8")
        elif path == "/api/state":
            self._json(200, snapshot())
        elif path == "/status":
            s = snapshot()
            self._json(200, {
                "ok": True,
                "online": bool(s["has_data"] and (s["age_sec"] or 0) < BAD_AFTER_SEC),
                "age_sec": s["age_sec"],
                "relay": s["relay"],
                "level": s["level"],
                "charging": s["charging"],
                "device": s["device"],
                "model": s["model"],
                "last_event": s["event"],
            })
        elif path == "/favicon.ico":
            self._send(204, b"", "image/x-icon")
        else:
            self._json(404, {"ok": False, "err": "not_found"})

    # ---------- POST ----------
    def do_POST(self):
        path = urlparse(self.path).path

        if path == "/api/simulate":
            fake = {
                "device_id": "phoneA",
                "manufacturer": "Simulated",
                "model": "TestBench",
                "android": "14",
                "event": "SIMULATED",
                "level": 55,
                "charging": False,
                "plugged": "UNPLUGGED",
                "temp_c": 28.0,
                "voltage_mv": 3850,
                "ts": int(time.time()),
            }
            record(fake, "127.0.0.1", simulated=True)
            return self._json(200, {"ok": True})

        if path != "/battery":
            return self._json(404, {"ok": False, "err": "not_found"})

        if self.headers.get("X-Auth-Token") != TOKEN:
            ip = self.client_address[0]
            print(f"[{now_str()}] [401] bad token from {ip}")
            return self._json(401, {"ok": False, "err": "bad_token"})

        length = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            data = json.loads(raw or b"{}")
            if not isinstance(data, dict):
                raise ValueError("not an object")
        except Exception:
            return self._json(400, {"ok": False, "err": "bad_json"})

        record(data, self.client_address[0])
        return self._json(200, {
            "ok": True,
            "relay": STATE["relay"],
            "level": STATE["level"],
            "low": LOW,
            "high": HIGH,
        })


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

    ip = local_ip()
    print("=" * 62)
    print(" AutoPhoneZ 局域网网页查看端（测试用，替代 ESP32-S3）")
    print(f" 手机填 IP : {ip}")
    print(f" 端口      : {PORT}")
    print(f" Token     : {TOKEN}")
    print(f" 电脑上看  : http://127.0.0.1:{PORT}/")
    print(f" 同网段看  : http://{ip}:{PORT}/")
    print(f" 调试接口  : curl http://{ip}:{PORT}/status")
    print(f" 阈值      : {LOW}% 开充 / {HIGH}% 断电（仅面板模拟继电器）")
    print("-" * 62)
    print(" Windows 首次运行会弹防火墙提示，必须允许「专用网络」")
    print("=" * 62)

    httpd = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nbye")
        httpd.server_close()


if __name__ == "__main__":
    main()
