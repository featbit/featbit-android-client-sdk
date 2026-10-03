"""Loopback-only HTTP/WebSocket fixture for sample UI tests, not a FeatBit deployment.
Run: python samples/tools/protocol_fixture.py
Uses fictional users/keys, no persistence, and no authentication validation except /denied.
"""
import base64
import hashlib
import json
import struct
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

specs = json.loads((Path(__file__).resolve().parents[1] / "shared/assets/demo-flags.json").read_text(encoding="utf-8"))
events = []
def envelope(user):
    flags = []
    for spec in specs:
        value = spec["initial"]
        if spec["key"] == "sample-new-checkout":
            value = "true" if user.get("keyId") == "sample-sam" else "false"
        flags.append(dict(id=spec["key"], variation=value, variationType=spec["type"].lower(),
                          timestamp=int(time.time()*1000), variationOptions=[dict(id="sample-variation", value=value)]))
    return dict(messageType="data-sync", data=dict(eventType="full", userKeyId=user["keyId"], featureFlags=flags))
class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *_): pass
    def respond(self, status, data):
        raw = json.dumps(data).encode()
        self.send_response(status); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)
    def do_POST(self):
        payload = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
        if self.path.startswith("/denied/"): self.respond(403, {}); return
        if self.path.startswith("/unavailable/"): self.respond(503, {}); return
        if "/latest-all" in self.path: self.respond(200, envelope(payload))
        elif self.path.endswith("/insight/track"):
            events.append(payload); self.respond(200, {})
        else: self.respond(404, {})
    def do_GET(self):
        if self.path == "/test-events": self.respond(200, events); return
        if self.headers.get("Upgrade", "").lower() != "websocket": self.respond(404, {}); return
        key = self.headers["Sec-WebSocket-Key"]
        accept = base64.b64encode(hashlib.sha1((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        self.send_response(101); self.send_header("Upgrade", "websocket"); self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept); self.end_headers()
        def send(data):
            raw=json.dumps(data).encode(); n=len(raw)
            self.wfile.write(bytes([129, n]) if n<126 else bytes([129,126])+struct.pack("!H",n))
            self.wfile.write(raw); self.wfile.flush()
        try:
            while True:
                head=self.rfile.read(2)
                if len(head)<2: break
                opcode=head[0]&15; n=head[1]&127
                if n==126: n=struct.unpack("!H",self.rfile.read(2))[0]
                elif n==127: n=struct.unpack("!Q",self.rfile.read(8))[0]
                mask=self.rfile.read(4) if head[1]&128 else None
                raw=self.rfile.read(n)
                if mask: raw=bytes(v^mask[i%4] for i,v in enumerate(raw))
                if opcode==8: break
                if opcode!=1: continue
                message=json.loads(raw)
                if message.get("messageType")=="data-sync": send(envelope(message["data"]["user"]))
                elif message.get("messageType")=="ping": send(dict(messageType="ping",data=None))
        except (OSError, ValueError): pass
        self.close_connection=True
if __name__ == "__main__":
    server=ThreadingHTTPServer(("127.0.0.1", 5198),Handler)
    print("Sample protocol fixture on loopback port 5198",flush=True)
    server.serve_forever()
