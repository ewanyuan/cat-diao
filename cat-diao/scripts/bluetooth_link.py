"""Authenticated Bluetooth address recovery and durable save delivery."""
import hashlib
from contextlib import contextmanager
import hmac
import ipaddress
import json
import secrets
import struct
import threading
import time

import bluetooth_windows

MAX_FRAME = 1400000
LAN_NETWORKS = tuple(ipaddress.ip_network(value) for value in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))


@contextmanager
def frame_deadline(channel, timeout=30):
    def abort():
        try:
            channel.shutdown(2)
        except OSError:
            pass
        channel.close()
    watchdog = threading.Timer(timeout, abort)
    watchdog.daemon = True
    watchdog.start()
    try:
        yield
    finally:
        watchdog.cancel()


def proof(key, text):
    return hmac.new(key.encode("utf-8"), text.encode("utf-8"), hashlib.sha256).hexdigest()


def send_frame(channel, value):
    raw = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if not 0 < len(raw) <= MAX_FRAME:
        raise ValueError("Bluetooth frame too large")
    channel.sendall(struct.pack("!I", len(raw)) + raw)


def read_frame(channel):
    def exact(length):
        output = bytearray()
        while len(output) < length:
            block = channel.recv(min(65536, length - len(output)))
            if not block:
                raise EOFError("Bluetooth connection closed")
            output.extend(block)
        return bytes(output)
    length = struct.unpack("!I", exact(4))[0]
    if not 0 < length <= MAX_FRAME:
        raise ValueError("Invalid Bluetooth frame length")
    value = json.loads(exact(length).decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError("Invalid Bluetooth message")
    return value


def valid_addresses(values):
    if not isinstance(values, list) or len(values) > 16:
        raise ValueError("Invalid address list")
    result = []
    for value in values:
        ip = ipaddress.ip_address(value)
        # Android's isSiteLocalAddress accepts RFC1918 only. Python is_private
        # also includes proxy benchmark ranges such as 198.18/15: signing those
        # would make the two peers authenticate different address lists.
        if ip.version != 4 or not any(ip in network for network in LAN_NETWORKS):
            continue
        result.append(str(ip))
    return list(dict.fromkeys(result))


class Session:
    def __init__(self, channel, token, addresses):
        self.channel = channel
        with frame_deadline(channel):
            self.handshake(token, addresses)

    def handshake(self, token, addresses):
        channel = self.channel
        nonce = secrets.token_hex(16)
        addresses = valid_addresses(addresses)
        send_frame(channel, {"nonce": nonce, "addresses": addresses,
                             "proof": proof(token, "hello:" + nonce + ":" + ",".join(addresses))})
        reply = read_frame(channel)
        other = reply.get("nonce", "")
        if not isinstance(other, str) or len(other) != 32 or any(c not in "0123456789abcdef" for c in other):
            raise ValueError("Invalid Bluetooth challenge")
        self.addresses = valid_addresses(reply.get("addresses"))
        expected = proof(token, "welcome:" + nonce + ":" + other + ":" + ",".join(self.addresses))
        if not isinstance(reply.get("proof"), str) or not hmac.compare_digest(expected, reply["proof"]):
            raise ValueError("Bluetooth pairing not confirmed")
        self.key = proof(token, "session:" + nonce + ":" + other)
        self.sequence = 0

    def exchange(self, body):
        with frame_deadline(self.channel):
            return self.exchange_frame(body)

    def exchange_frame(self, body):
        self.sequence += 1
        sequence = self.sequence
        raw = json.dumps(body, ensure_ascii=False, separators=(",", ":"))
        send_frame(self.channel, {"sequence": sequence, "payload": raw,
                                 "proof": proof(self.key, f"pc:{sequence}:" + raw)})
        reply = read_frame(self.channel)
        payload = reply.get("payload")
        if reply.get("sequence") != sequence or not isinstance(payload, str) or not isinstance(reply.get("proof"), str):
            raise ValueError("Invalid Bluetooth reply")
        if not hmac.compare_digest(proof(self.key, f"phone:{sequence}:" + payload), reply["proof"]):
            raise ValueError("Bluetooth reply not authenticated")
        result = json.loads(payload)
        if not isinstance(result, dict):
            raise ValueError("Invalid Bluetooth payload")
        return result


class BluetoothReceiver:
    def __init__(self, bridge, receive):
        self.bridge = bridge
        self.receive = receive
        self.lock = threading.Lock()
        self.state = {"state": "starting", "last_seen_at": 0}
        self.stop = threading.Event()
        self.channel = None
        self.thread = threading.Thread(target=self.run, name="catdiao-bluetooth", daemon=True)

    def snapshot(self):
        with self.lock:
            state = dict(self.state)
        if state["state"] == "connected" and int(time.time() * 1000) - state["last_seen_at"] > 30000:
            state["state"] = "not_connected"
        return state

    def changed(self, state, **extra):
        with self.lock:
            recent_error = self.state.get("error", "")
            self.state = {"state": state, "last_seen_at": 0, **extra}
            if state != "connected" and "error" not in extra and recent_error:
                self.state["error"] = recent_error

    def close(self):
        self.stop.set()
        if self.channel:
            self.channel.close()

    def addresses(self):
        return valid_addresses([ip for ip, _ in self.bridge.local_interfaces(deadline=time.monotonic() + 2)])

    def recover_address(self, token, addresses):
        deadline = time.monotonic() + 4
        for ip in valid_addresses(addresses):
            if time.monotonic() >= deadline:
                return
            if not self.bridge.source_ip(ip, deadline=deadline):
                continue
            address = f"http://{ip}:8767"
            try:
                reply = self.bridge.request(address, "GET", "/heartbeat", token, timeout=1, deadline=deadline)
                if reply.get("connected") is True:
                    self.bridge.update_pair_address(token, address, deadline)
                    return
            except (OSError, ValueError, RuntimeError):
                continue

    def serve(self, channel, saved, device):
        token = saved["token"]
        session = Session(channel, token, self.addresses())
        # Save the Bluetooth identity only after the phone proves the existing app pairing.
        with self.bridge.config_writer() as finish_by:
            current = self.bridge.config_read()
            if current.get("token") != token:
                return
            current["bluetooth_address"] = device["address"]
            self.bridge.save_config_locked(current, finish_by)
        self.recover_address(token, session.addresses)
        accepted = []
        while not self.stop.is_set() and self.bridge.config_read().get("token") == token:
            reply = session.exchange({"addresses": self.addresses(), "accepted": accepted})
            if self.bridge.config_read().get("token") != token:
                return
            items = reply.get("items", [])
            if not isinstance(items, list) or len(items) > 20 or any(not isinstance(item, dict) for item in items):
                raise ValueError("Invalid Bluetooth saves")
            accepted = self.receive(items) if items else []
            self.changed("connected", last_seen_at=int(time.time() * 1000), device=device["name"])
            self.recover_address(token, reply.get("addresses", []))
            if not items:
                self.stop.wait(5)

    def run(self):
        while not self.stop.is_set():
            try:
                saved = self.bridge.config_read()
                if not saved.get("token"):
                    self.changed("unpaired")
                    self.stop.wait(10)
                    continue
                devices = bluetooth_windows.paired_phones(saved.get("bluetooth_address"))
                self.changed("searching" if devices else "needs_bluetooth_pairing")
                for device in devices:
                    if self.stop.is_set():
                        return
                    try:
                        with bluetooth_windows.connect(device["address"], timeout=6) as channel:
                            self.channel = channel
                            self.serve(channel, saved, device)
                    except Exception as error:
                        # Ledger locks and optional workbook failures must not kill reconnection.
                        self.changed("not_connected", error=type(error).__name__ + ": " + str(error)[:120])
                        continue
                    finally:
                        self.channel = None
                self.changed("not_connected" if devices else "needs_bluetooth_pairing")
            except Exception as error:
                self.changed("unavailable", error=type(error).__name__)
            self.stop.wait(10)
