#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
本地模拟 ESP32-S3 接收端。

用途：ESP32 还没到货/还没烧录时，先用它在电脑上验证手机 App 的整条链路。
协议与 esp32/src/main.cpp 完全一致（POST /battery + GET /status + X-Auth-Token）。

用法：
    python3 tools/sim_server.py            # 默认端口 8080
    python3 tools/sim_server.py 9000       # 指定端口
    python3 tools/sim_server.py 8080 mytoken123

然后手机 App 里：IP 填本机局域网 IP（脚本启动时会打印），端口 / Token 与这里一致。
"""

import json
import socket
import sys
from datetime import datetime
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
TOKEN = sys.argv[2] if len(sys.argv) > 2 else "test-token-123456"
LOW, HIGH = 30, 90

relay = False
state = {
    "level": -1,
    "charging": False,
    "device": "-",
    "model": "-",
    "event": "-",
    "last_seen": None,
}


def now() -> str:
    return datetime.now().strftime("%H:%M:%S")


def set_relay(on: bool, reason: str) -> None:
    global relay
    if on != relay:
        relay = on
        print(f"[{now()}] [RELAY] {'ON  充电接通' if on else 'OFF 充电断开'}  ({reason})")


class Handler(BaseHTTPRequestHandler):
    server_version = "AutoPhoneZSim"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass  # 关掉默认的每请求日志，用我们自己的

    def _send(self, code: int, obj: dict) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path != "/battery":
            return self._send(404, {"ok": False, "err": "not_found"})

        if self.headers.get("X-Auth-Token") != TOKEN:
            print(f"[{now()}] [401] bad token from {self.client_address[0]}")
            return self._send(401, {"ok": False, "err": "bad_token"})

        length = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            data = json.loads(raw or b"{}")
        except Exception:
            return self._send(400, {"ok": False, "err": "bad_json"})

        event = data.get("event", "UNKNOWN")
        device = data.get("device_id", "unknown")
        model = f"{data.get('manufacturer', '')} {data.get('model', '')}".strip()
        level = int(data.get("level", -1))
        charging = bool(data.get("charging", False))
        plugged = data.get("plugged", "UNKNOWN")
        temp = data.get("temp_c", 0)
        volt = data.get("voltage_mv", 0)

        state.update({
            "level": level,
            "charging": charging,
            "device": device,
            "model": model,
            "event": event,
            "last_seen": datetime.now(),
        })

        print(f"[{now()}] [EVT] {event:<12} dev={device} model={model} "
              f"level={level}% chg={1 if charging else 0} plug={plugged} "
              f"temp={temp}C v={volt}mV relay={'ON' if relay else 'OFF'}")

        # 与固件一致：只看电量，天然带回滞
        if level >= 0:
            if level <= LOW and not relay:
                set_relay(True, "level<=LOW")
            elif level >= HIGH and relay:
                set_relay(False, "level>=HIGH")

        return self._send(200, {
            "ok": True, "relay": relay, "level": state["level"],
            "low": LOW, "high": HIGH,
        })

    def do_GET(self):
        if self.path != "/status":
            return self._send(404, {"ok": False, "err": "not_found"})

        age = None
        online = False
        if state["last_seen"]:
            age = int((datetime.now() - state["last_seen"]).total_seconds())
            online = age < 30 * 60

        return self._send(200, {
            "ok": True,
            "online": online,
            "age_sec": age,
            "relay": relay,
            "level": state["level"],
            "charging": state["charging"],
            "device": state["device"],
            "model": state["model"],
            "last_event": state["event"],
        })


def local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


if __name__ == "__main__":
    print("=" * 60)
    print(" AutoPhoneZ 模拟接收端（替代 ESP32-S3）")
    print(f" 监听端口 : {PORT}")
    print(f" Token    : {TOKEN}")
    print(f" 手机填IP : {local_ip()}   （必须和手机同一局域网）")
    print(f" 阈值     : {LOW}% 开充 / {HIGH}% 断电")
    print(f" 调试     : curl http://{local_ip()}:{PORT}/status")
    print("=" * 60)
    try:
        HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
    except KeyboardInterrupt:
        print("\nbye")
