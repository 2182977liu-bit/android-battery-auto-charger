#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AutoPhoneZ 局域网网页查看端 · 多设备版（测试用）

作用：不用 ESP32，先在一台局域网电脑上把**多台手机**上报的数据直观看到，
      用来判断「手机端软件是否正常工作」。

协议与 ESP32 完全一致：
    POST /battery       Header: X-Auth-Token     Body: JSON
    GET  /status        调试接口
    GET  /              多设备面板（每秒刷新）
    GET  /api/state     面板用的 JSON
    POST /api/simulate  模拟一条（可 ?id=phoneB 指定设备）
    POST /api/reset     清空所有数据重新开始
    POST /api/token     网页上修改上报 Token（body: {"token":"..."}）

多设备靠 device_id 区分：每台手机在自己的 App 里填不同的设备 ID，
接收端按 device_id 各存一份状态、各自的曲线和统计，互不覆盖。

用法：
    python3 tools/web_dashboard.py                  # 端口 8080，Token test-token-123456
    python3 tools/web_dashboard.py 9000 mytoken     # 指定端口和 Token

Windows 首次运行会弹防火墙提示，必须勾选「专用网络」并允许，否则手机连不上。
"""

import json
import os
import socket
import sys
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
TOKEN = sys.argv[2] if len(sys.argv) > 2 else "test-token-123456"

# Token 持久化：设了 AUTOZ_TOKEN_FILE 时，网页上改的 Token 会写进该文件，
# 重启后优先读文件，命令行参数只作为初始值。
TOKEN_FILE = os.environ.get("AUTOZ_TOKEN_FILE", "")
if TOKEN_FILE:
    try:
        with open(TOKEN_FILE, encoding="utf-8") as _f:
            _saved = _f.read().strip()
        if _saved:
            TOKEN = _saved
    except Exception:
        pass

LOW, HIGH = 30, 90
WARN_AFTER_SEC = 400      # 超过 6.5 分钟没数据 -> 黄色（心跳是 5 分钟）
BAD_AFTER_SEC = 900       # 超过 15 分钟没数据 -> 红色
MAX_EVENTS = 60
MAX_HISTORY = 120

LOCK = threading.Lock()

STATE = {
    "devices": {},   # device_id -> 设备状态
    "events": [],    # 全局事件流（带设备字段）
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


def new_device(dev_id: str) -> dict:
    return {
        "id": dev_id,
        "last_seen": 0.0,
        "last_event": "-",
        "level": -1,
        "charging": False,
        "plugged": "-",
        "model": "-",
        "android": "-",
        "temp": 0.0,
        "volt": 0,
        "count": 0,
        "low_hits": 0,
        "high_hits": 0,
        "plug_hits": 0,
        "relay": False,
        "history": [],
        "last_ip": "-",
    }


def apply_thresholds(dev: dict, level: int) -> bool:
    """与 ESP32 固件一致的控制逻辑，这里只用于面板上展示「假想继电器」。"""
    if level < 0:
        return dev["relay"]
    if level <= LOW:
        return True
    if level >= HIGH:
        return False
    return dev["relay"]


def record(data: dict, ip: str, simulated: bool = False) -> str:
    event = str(data.get("event", "UNKNOWN"))
    level = int(data.get("level", -1) or -1)
    charging = bool(data.get("charging", False))
    dev_id = str(data.get("device_id") or "unknown").strip() or "unknown"

    with LOCK:
        dev = STATE["devices"].get(dev_id)
        if dev is None:
            dev = new_device(dev_id)
            STATE["devices"][dev_id] = dev

        prev_relay = dev["relay"]
        dev["last_seen"] = time.time()
        dev["last_event"] = event
        dev["level"] = level
        dev["charging"] = charging
        dev["plugged"] = str(data.get("plugged", "-"))
        model = f"{data.get('manufacturer', '')} {data.get('model', '')}".strip()
        dev["model"] = model or "-"
        dev["android"] = str(data.get("android", "-"))
        dev["temp"] = float(data.get("temp_c", 0) or 0)
        dev["volt"] = int(data.get("voltage_mv", 0) or 0)
        dev["last_ip"] = ip
        dev["count"] += 1
        dev["relay"] = apply_thresholds(dev, level)

        if event == "LOW_BATTERY":
            dev["low_hits"] += 1
        elif event == "HIGH_BATTERY":
            dev["high_hits"] += 1
        elif event in ("PLUGGED", "UNPLUGGED"):
            dev["plug_hits"] += 1

        STATE["events"].append({
            "t": now_str(),
            "device": dev_id,
            "event": event,
            "level": level,
            "charging": charging,
            "temp": dev["temp"],
            "ip": ip,
        })
        if len(STATE["events"]) > MAX_EVENTS:
            del STATE["events"][: len(STATE["events"]) - MAX_EVENTS]

        hist = dev["history"]
        t = time.time()
        if (not hist) or hist[-1]["level"] != level or (t - hist[-1]["t"]) > 60:
            hist.append({"t": t, "level": level})
            if len(hist) > MAX_HISTORY:
                del hist[: len(hist) - MAX_HISTORY]

        relay_changed = prev_relay != dev["relay"]
        device_count = len(STATE["devices"])

    flag = " [模拟]" if simulated else ""
    print(f"[{now_str()}] {dev_id:<18} {event:<12} level={level:>3}% "
          f"chg={1 if charging else 0} plug={dev['plugged']:<9}{flag}"
          f"  (设备总数 {device_count})")
    if relay_changed:
        print(f"[{now_str()}] [{dev_id}] 继电器·模拟 "
              f"{'ON  接通' if dev['relay'] else 'OFF 断开'}")
    return dev_id


def snapshot() -> dict:
    t = time.time()
    with LOCK:
        out_devices = []
        for dev in STATE["devices"].values():
            age = int(t - dev["last_seen"]) if dev["last_seen"] else None
            out_devices.append({
                "id": dev["id"],
                "model": dev["model"],
                "android": dev["android"],
                "level": dev["level"],
                "charging": dev["charging"],
                "plugged": dev["plugged"],
                "temp": dev["temp"],
                "volt": dev["volt"],
                "event": dev["last_event"],
                "relay": dev["relay"],
                "age_sec": age,
                "count": dev["count"],
                "low_hits": dev["low_hits"],
                "high_hits": dev["high_hits"],
                "plug_hits": dev["plug_hits"],
                "last_ip": dev["last_ip"],
                "history": list(dev["history"][-40:]),
            })

        # 最近上报的设备排前面
        out_devices.sort(key=lambda d: (d["age_sec"] is None, d["age_sec"] or 0))

        online = sum(1 for d in out_devices if d["age_sec"] is not None and d["age_sec"] < BAD_AFTER_SEC)
        total_count = sum(d["count"] for d in out_devices)

        return {
            "devices": out_devices,
            "totals": {
                "devices": len(out_devices),
                "online": online,
                "packets": total_count,
            },
            "events": list(STATE["events"][-40:]),
            "low": LOW,
            "high": HIGH,
        }


HTML = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>电量哨兵 · 多设备查看端</title>
<style>
  :root{
    --bg:#0d1117; --card:#161b22; --line:#30363d; --fg:#e6edf3; --dim:#8b949e;
    --ok:#3fb950; --warn:#f0a04b; --bad:#f85149; --cyan:#39d2c0; --blue:#58a6ff;
  }
  *{box-sizing:border-box;margin:0;padding:0}
  body{background:var(--bg);color:var(--fg);
       font-family:system-ui,-apple-system,"Segoe UI","Microsoft YaHei",sans-serif;
       padding:18px;min-height:100vh}
  .wrap{max-width:1180px;margin:0 auto}
  header{display:flex;align-items:center;justify-content:space-between;
         flex-wrap:wrap;gap:10px;margin-bottom:16px}
  h1{font-size:19px;font-weight:600}
  h1 span{color:var(--dim);font-size:13px;font-weight:400;margin-left:10px}
  .totals{font-size:13px;color:var(--dim)}
  .totals b{color:var(--cyan);font-weight:600}

  .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(330px,1fr));gap:14px}

  .dev{background:var(--card);border:1px solid var(--line);border-radius:14px;
       padding:16px;transition:opacity .3s}
  .dev.stale{opacity:.55}
  .dev-head{display:flex;align-items:center;justify-content:space-between;
            gap:8px;margin-bottom:2px}
  .dev-id{font-size:14px;font-weight:600;color:var(--cyan);
          font-family:ui-monospace,Consolas,monospace;word-break:break-all}
  .badge{font-size:11px;padding:3px 9px;border-radius:999px;white-space:nowrap;
         border:1px solid var(--line);color:var(--dim)}
  .badge.ok{color:var(--ok);border-color:rgba(63,185,80,.5)}
  .badge.warn{color:var(--warn);border-color:rgba(240,160,75,.5)}
  .badge.bad{color:var(--bad);border-color:rgba(248,81,73,.5)}
  .dev-model{font-size:12px;color:var(--dim);margin-bottom:12px;word-break:break-all}

  .levelrow{display:flex;align-items:baseline;gap:8px}
  .big{font-size:40px;font-weight:700;line-height:1}
  .chg{font-size:12px;padding:3px 10px;border-radius:999px;
       border:1px solid var(--line);color:var(--dim)}
  .chg.on{color:var(--ok);border-color:rgba(63,185,80,.5)}

  .bar{height:8px;border-radius:6px;background:#21262d;margin-top:10px;
       overflow:hidden;position:relative}
  .bar-fill{height:100%;width:0;border-radius:6px;
            transition:width .4s ease,background .3s}
  .bar-mark{position:absolute;top:0;bottom:0;width:1px;background:rgba(255,255,255,.25)}

  .meta{display:grid;grid-template-columns:1fr 1fr;gap:5px 12px;
        margin-top:12px;font-size:12px;color:var(--dim)}
  .meta b{color:var(--fg);font-weight:500}
  .meta .relay-on{color:var(--ok)}
  .meta .relay-off{color:var(--dim)}

  canvas.spark{width:100%;height:52px;display:block;margin-top:10px}

  .panel{background:var(--card);border:1px solid var(--line);border-radius:14px;
         padding:16px;margin-top:16px}
  .panel h2{font-size:14px;font-weight:600;margin-bottom:12px}
  .panel h2 em{font-style:normal;color:var(--dim);font-weight:400;
                font-size:12px;margin-left:8px}
  table{width:100%;border-collapse:collapse;font-size:12.5px;
        font-family:ui-monospace,Consolas,monospace}
  th{color:var(--dim);font-weight:400;text-align:left;padding:6px 8px;
     border-bottom:1px solid var(--line);position:sticky;top:0;background:var(--card)}
  td{padding:6px 8px;border-bottom:1px solid #21262d}
  .scroll{max-height:320px;overflow:auto}
  .ev{font-weight:600}

  footer{margin-top:16px;padding:14px 16px;background:var(--card);
         border:1px solid var(--line);border-radius:12px;font-size:13px;
         color:var(--dim);line-height:1.9}
  footer b{color:var(--cyan);font-weight:600;font-family:ui-monospace,Consolas,monospace}
  .row{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-top:10px}
  button{background:#21262d;color:var(--fg);border:1px solid var(--line);
         padding:8px 14px;border-radius:8px;font-size:13px;cursor:pointer}
  button:hover{border-color:var(--cyan);color:var(--cyan)}
  button:active{transform:translateY(1px)}
</style>
</head>
<body>
<div class="wrap">
  <header>
    <h1>电量哨兵<span>多设备查看端 · 测试用</span></h1>
    <div class="totals" id="totals">暂无设备</div>
  </header>

  <div class="panel" style="padding:14px 16px">
    <div class="row" style="margin-top:0">
      <span style="font-size:13px;color:#8b949e">上报 Token</span>
      <input id="tokenInput" value="__TOKEN__" spellcheck="false"
             style="background:#0d1117;border:1px solid #30363d;color:#e6edf3;
                    padding:7px 10px;border-radius:8px;font-size:13px;width:220px">
      <button onclick="saveToken()">保存</button>
      <span id="tokenMsg" style="font-size:12px;color:#8b949e"></span>
    </div>
    <div style="font-size:12px;color:#8b949e;margin-top:6px">
      改完这里，手机 App 里的 Token 要同步改成一样，否则上报会被 401 拒绝。
    </div>
  </div>

  <div class="grid" id="devices">
    <div class="dev" style="grid-column:1/-1;text-align:center;padding:60px 20px;color:#8b949e">
      还没有任何设备上报。<br><br>
      手机 App 里把 IP 填成 <b style="color:var(--cyan)">__IP__</b>、端口 <b style="color:var(--cyan)">__PORT__</b>、
      Token 填 <b style="color:var(--cyan)">__TOKEN__</b>，点「保存并启动」。
    </div>
  </div>

  <div class="panel">
    <h2>事件流<em>最近 40 条 · 多设备混合</em></h2>
    <div class="scroll">
      <table>
        <thead><tr>
          <th>时间</th><th>设备</th><th>事件</th><th>电量</th>
          <th>充电</th><th>温度</th><th>来源</th>
        </tr></thead>
        <tbody id="events"></tbody>
      </table>
    </div>
  </div>

  <footer>
    每台手机在 App 里填**不同的设备 ID**（例如 phoneA / phoneB），否则会被当成同一台互相覆盖。<br>
    阈值：低电量 <b>__LOW__%</b> ／ 高电量 <b>__HIGH__%</b>　·　连不上时先确认手机和这台电脑在同一局域网
    <div class="row">
      <button onclick="simulate('phoneA')">模拟 phoneA</button>
      <button onclick="simulate('phoneB')">模拟 phoneB</button>
      <button onclick="resetAll()">清空数据</button>
      <span style="font-size:12px">本页面仅供局域网测试，不要映射到公网</span>
    </div>
  </footer>
</div>

<script>
const BAR_MARKS = [__LOW__, __HIGH__];
let cardCache = {};
let lastIdSet = "";

async function saveToken(){
  const input = document.getElementById("tokenInput");
  const msg = document.getElementById("tokenMsg");
  const t = input.value.trim();
  if (!t) { msg.style.color = "#f85149"; msg.textContent = "Token 不能为空"; return; }
  try {
    const r = await fetch("api/token", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({token: t})
    });
    if (r.ok) {
      msg.style.color = "#3fb950";
      msg.textContent = "已保存，页面即将刷新…";
      setTimeout(() => location.reload(), 700);
    } else {
      msg.style.color = "#f85149";
      msg.textContent = "保存失败（可能含空格或过长）";
    }
  } catch (e) {
    msg.style.color = "#f85149";
    msg.textContent = "保存失败：" + e;
  }
}
let lastEventKey = "";
let lastDevices = [];

function colorFor(level){
  if (level < 0) return "#58a6ff";
  if (level <= __LOW__) return "#f0a04b";
  if (level >= __HIGH__) return "#3fb950";
  return "#58a6ff";
}

function fmtAge(sec){
  if (sec === null || sec === undefined) return "无数据";
  if (sec < 60) return sec + " 秒前";
  if (sec < 3600) return Math.floor(sec/60) + " 分钟前";
  return Math.floor(sec/3600) + " 小时前";
}

function deviceCard(d){
  const marks = BAR_MARKS.map(v =>
    `<div class="bar-mark" style="left:${v}%"></div>`).join("");
  return `
  <div class="dev" data-id="${esc(d.id)}">
    <div class="dev-head">
      <div class="dev-id">${esc(d.id)}</div>
      <div class="badge" data-f="badge">-</div>
    </div>
    <div class="dev-model" data-f="model">-</div>

    <div class="levelrow">
      <div class="big" data-f="level">--</div>
      <div class="chg" data-f="chg">未充电</div>
    </div>
    <div class="bar">${marks}<div class="bar-fill" data-f="fill"></div></div>

    <div class="meta">
      <div>机型温度 <b data-f="temp">-</b></div>
      <div>电压 <b data-f="volt">-</b></div>
      <div>供电 <b data-f="plugged">-</b></div>
      <div>最后事件 <b data-f="event">-</b></div>
      <div>最后上报 <b data-f="age">-</b></div>
      <div>报文数 <b data-f="count">0</b></div>
      <div>低/高触发 <b data-f="hits">0/0</b></div>
      <div>插拔 <b data-f="plughits">0</b></div>
      <div style="grid-column:1/-1">继电器（模拟） <b data-f="relay">OFF</b></div>
    </div>

    <canvas class="spark" data-f="spark"></canvas>
  </div>`;
}

function esc(s){
  return String(s).replace(/[&<>"']/g, c => (
    {"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
}

function syncCards(devices){
  const idSet = devices.map(d => d.id).join("|");
  const box = document.getElementById("devices");
  if (idSet !== lastIdSet){
    lastIdSet = idSet;
    cardCache = {};
    box.innerHTML = devices.length
      ? devices.map(deviceCard).join("")
      : `<div class="dev" style="grid-column:1/-1;text-align:center;padding:60px 20px;color:#8b949e">
           还没有任何设备上报。<br><br>在手机 App 里填好 IP / 端口 / Token 后点「保存并启动」。
         </div>`;
    [...box.querySelectorAll(".dev[data-id]")].forEach(el => {
      cardCache[el.dataset.id] = el;
    });
  }
}

function q(el, f){ return el.querySelector('[data-f="' + f + '"]'); }

function updateCard(d){
  const el = cardCache[d.id];
  if (!el) return;

  const stale = d.age_sec === null || d.age_sec > 900;
  el.classList.toggle("stale", stale && d.age_sec !== null);

  q(el, "model").textContent = d.model + (d.android !== "-" ? "  ·  Android " + d.android : "");

  q(el, "level").textContent = d.level >= 0 ? d.level : "--";
  q(el, "level").style.color = colorFor(d.level);

  const chg = q(el, "chg");
  chg.textContent = d.charging ? "充电中 · " + d.plugged : "未充电";
  chg.className = "chg" + (d.charging ? " on" : "");

  const fill = q(el, "fill");
  const pct = d.level >= 0 ? Math.min(100, Math.max(0, d.level)) : 0;
  fill.style.width = pct + "%";
  fill.style.background = colorFor(d.level);

  q(el, "temp").textContent = d.temp ? d.temp + " °C" : "-";
  q(el, "volt").textContent = d.volt ? (d.volt/1000).toFixed(3) + " V" : "-";
  q(el, "plugged").textContent = d.plugged;
  q(el, "event").textContent = d.event;
  q(el, "age").textContent = fmtAge(d.age_sec);
  q(el, "count").textContent = d.count;
  q(el, "hits").textContent = d.low_hits + " / " + d.high_hits;
  q(el, "plughits").textContent = d.plug_hits;

  const rel = q(el, "relay");
  rel.textContent = d.relay ? "ON（假想接通）" : "OFF（假想断开）";
  rel.className = d.relay ? "relay-on" : "relay-off";

  const badge = q(el, "badge");
  if (d.age_sec === null){
    badge.className = "badge"; badge.textContent = "无数据";
  } else if (d.age_sec > 900){
    badge.className = "badge bad"; badge.textContent = "离线";
  } else if (d.age_sec > 400){
    badge.className = "badge warn"; badge.textContent = "延迟";
  } else {
    badge.className = "badge ok"; badge.textContent = "在线";
  }

  drawSpark(q(el, "spark"), d.history);
}

function drawSpark(cv, history){
  const dpr = window.devicePixelRatio || 1;
  const w = cv.clientWidth || 300, h = cv.clientHeight || 52;
  if (cv.width !== Math.floor(w*dpr) || cv.height !== Math.floor(h*dpr)){
    cv.width = Math.floor(w*dpr); cv.height = Math.floor(h*dpr);
  }
  const ctx = cv.getContext("2d");
  ctx.setTransform(dpr,0,0,dpr,0,0);
  ctx.clearRect(0,0,w,h);

  const padL = 4, padR = 4, padT = 6, padB = 6;
  const plotH = h - padT - padB;
  const yOf = v => padT + plotH * (1 - v/100);

  ctx.strokeStyle = "#21262d"; ctx.lineWidth = 1;
  [0,50,100].forEach(v => {
    const y = yOf(v);
    ctx.beginPath(); ctx.moveTo(padL,y); ctx.lineTo(w-padR,y); ctx.stroke();
  });

  [[__LOW__, "#f0a04b"], [__HIGH__, "#3fb950"]].forEach(([v,c]) => {
    const y = yOf(v);
    ctx.strokeStyle = c; ctx.globalAlpha = .35; ctx.setLineDash([4,4]);
    ctx.beginPath(); ctx.moveTo(padL,y); ctx.lineTo(w-padR,y); ctx.stroke();
    ctx.setLineDash([]); ctx.globalAlpha = 1;
  });

  if (!history || history.length < 2) return;

  const t0 = history[0].t, t1 = history[history.length-1].t;
  const span = Math.max(1, t1 - t0);
  const xOf = t => padL + (w-padL-padR) * ((t-t0)/span);

  ctx.strokeStyle = "#39d2c0"; ctx.lineWidth = 1.6;
  ctx.beginPath();
  history.forEach((p,i) => {
    const x = xOf(p.t), y = yOf(p.level);
    if (i===0) ctx.moveTo(x,y); else ctx.lineTo(x,y);
  });
  ctx.stroke();

  const last = history[history.length-1];
  ctx.fillStyle = colorFor(last.level);
  ctx.beginPath();
  ctx.arc(xOf(last.t), yOf(last.level), 2.5, 0, Math.PI*2);
  ctx.fill();
}

function renderEvents(events){
  const key = events.map(e => e.t + e.device + e.event).join("");
  if (key === lastEventKey) return;
  lastEventKey = key;

  const colors = {
    LOW_BATTERY:"#f0a04b", HIGH_BATTERY:"#3fb950", PLUGGED:"#58a6ff",
    UNPLUGGED:"#8b949e", HELLO:"#39d2c0", HEARTBEAT:"#6e7681",
    TEST:"#d29922", SIMULATED:"#d29922"
  };
  const tbody = document.getElementById("events");
  const rows = events.slice().reverse().map(e => `<tr>
    <td>${e.t}</td>
    <td style="color:#39d2c0">${esc(e.device)}</td>
    <td class="ev" style="color:${colors[e.event] || "#e6edf3"}">${esc(e.event)}</td>
    <td>${e.level >= 0 ? e.level + "%" : "-"}</td>
    <td>${e.charging ? "是" : "否"}</td>
    <td>${e.temp ? e.temp + "C" : "-"}</td>
    <td style="color:#8b949e">${esc(e.ip)}</td>
  </tr>`).join("");
  tbody.innerHTML = rows ||
    `<tr><td colspan="7" style="color:#8b949e">还没有数据</td></tr>`;
}

function render(s){
  syncCards(s.devices);
  s.devices.forEach(updateCard);
  renderEvents(s.events);
  document.getElementById("totals").innerHTML =
    `设备 <b>${s.totals.devices}</b> 台　在线 <b>${s.totals.online}</b> 台　` +
    `累计报文 <b>${s.totals.packets}</b> 条`;
  lastDevices = s.devices;
}

async function tick(){
  try{
    const r = await fetch("api/state", {cache:"no-store"});
    render(await r.json());
  }catch(e){
    document.getElementById("totals").textContent = "面板服务断开";
  }
}

async function simulate(id){
  try{
    const r = await fetch("api/simulate?id=" + encodeURIComponent(id), {method:"POST"});
    if (!r.ok) throw new Error("HTTP " + r.status);
    await tick();
  }catch(e){ alert("模拟失败：" + e.message); }
}

async function resetAll(){
  if (!confirm("清空所有设备的统计数据？")) return;
  await fetch("api/reset", {method:"POST"});
  lastIdSet = ""; lastEventKey = "";
  await tick();
}

tick();
setInterval(tick, 1000);
window.addEventListener("resize", () => lastDevices.forEach(updateCard));
</script>
</body>
</html>
"""


def render_html() -> bytes:
    html = (HTML
            .replace("__IP__", os.environ.get("AUTOZ_PUBLIC_IP") or local_ip())
            .replace("__PORT__", os.environ.get("AUTOZ_PUBLIC_PORT") or str(PORT))
            .replace("__TOKEN__", TOKEN)
            .replace("__LOW__", str(LOW))
            .replace("__HIGH__", str(HIGH)))
    return html.encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    server_version = "AutoPhoneZWeb"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

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

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path in ("/", "/index.html"):
            self._send(200, render_html(), "text/html; charset=utf-8")
        elif path == "/api/state":
            self._json(200, snapshot())
        elif path == "/status":
            s = snapshot()
            devs = [{
                "id": d["id"],
                "online": d["age_sec"] is not None and d["age_sec"] < BAD_AFTER_SEC,
                "age_sec": d["age_sec"],
                "level": d["level"],
                "charging": d["charging"],
                "relay": d["relay"],
                "last_event": d["event"],
            } for d in s["devices"]]
            self._json(200, {
                "ok": True,
                "devices": devs,
                "totals": s["totals"],
            })
        elif path == "/favicon.ico":
            self._send(204, b"", "image/x-icon")
        else:
            self._json(404, {"ok": False, "err": "not_found"})

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/api/token":
            length = int(self.headers.get("Content-Length", 0) or 0)
            raw = self.rfile.read(length) if length else b""
            try:
                data = json.loads(raw or b"{}")
                new_token = str(data.get("token", "")).strip()
            except Exception:
                return self._json(400, {"ok": False, "err": "bad_json"})
            if not new_token or len(new_token) > 64 or any(c.isspace() for c in new_token):
                return self._json(400, {"ok": False, "err": "bad_token_value"})
            global TOKEN
            TOKEN = new_token
            if TOKEN_FILE:
                try:
                    with open(TOKEN_FILE, "w", encoding="utf-8") as f:
                        f.write(TOKEN)
                except Exception as e:
                    print(f"[{now_str()}] 写 Token 文件失败: {e}")
            print(f"[{now_str()}] Token 已更新为 {TOKEN}")
            return self._json(200, {"ok": True, "token": TOKEN})

        if path == "/api/reset":
            with LOCK:
                STATE["devices"].clear()
                STATE["events"].clear()
            print(f"[{now_str()}] 数据已清空")
            return self._json(200, {"ok": True})

        if path == "/api/simulate":
            qs = parse_qs(parsed.query)
            dev_id = (qs.get("id") or ["phoneA"])[0]
            # 让不同模拟设备的数据看起来有区别
            levels = {"phoneA": 55, "phoneB": 34, "phoneC": 78}
            level = levels.get(dev_id, 55)
            fake = {
                "device_id": dev_id,
                "manufacturer": "Simulated",
                "model": "TestBench-" + dev_id[-1:].upper(),
                "android": "14",
                "event": "SIMULATED",
                "level": level,
                "charging": level < LOW,
                "plugged": "AC" if level < LOW else "UNPLUGGED",
                "temp_c": 28.0,
                "voltage_mv": 3850,
                "ts": int(time.time()),
            }
            record(fake, "127.0.0.1", simulated=True)
            return self._json(200, {"ok": True})

        if path != "/battery":
            return self._json(404, {"ok": False, "err": "not_found"})

        if self.headers.get("X-Auth-Token") != TOKEN:
            print(f"[{now_str()}] [401] bad token from {self.client_address[0]}")
            return self._json(401, {"ok": False, "err": "bad_token"})

        length = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            data = json.loads(raw or b"{}")
            if not isinstance(data, dict):
                raise ValueError("not an object")
        except Exception:
            return self._json(400, {"ok": False, "err": "bad_json"})

        dev_id = record(data, self.client_address[0])
        with LOCK:
            dev = STATE["devices"][dev_id]
        return self._json(200, {
            "ok": True,
            "device_id": dev_id,
            "relay": dev["relay"],
            "level": dev["level"],
            "low": LOW,
            "high": HIGH,
        })


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

    ip = os.environ.get("AUTOZ_PUBLIC_IP") or local_ip()
    print("=" * 68)
    print(" AutoPhoneZ 局域网网页查看端 · 多设备版（测试用，替代 ESP32-S3）")
    print(f" 手机填 IP : {ip}")
    print(f" 端口      : {PORT}")
    print(f" Token     : {TOKEN}")
    print(f" 电脑上看  : http://127.0.0.1:{PORT}/")
    print(f" 同网段看  : http://{ip}:{PORT}/")
    print(f" 调试接口  : curl http://{ip}:{PORT}/status")
    print(f" 阈值      : {LOW}% 开充 / {HIGH}% 断电（仅面板模拟继电器）")
    print("-" * 68)
    print(" 多设备：每台手机在 App 里填不同的「设备 ID」，否则会被当成同一台")
    print(" Windows 首次运行会弹防火墙提示，必须允许「专用网络」")
    print("=" * 68)

    httpd = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nbye")
        httpd.server_close()


if __name__ == "__main__":
    main()
