"""Windows RFCOMM connector using the existing Bluetooth stack (no extra package)."""
import ctypes
from ctypes import wintypes as w
import select
import socket
import sys
import time
import uuid

SERVICE_UUID = "c96e9124-4d46-49ca-98a2-cad1a0110014"


class DeviceInfo(ctypes.Structure):
    _fields_ = [("size", w.DWORD), ("address", ctypes.c_ulonglong),
                ("device_class", w.ULONG), ("connected", w.BOOL),
                ("remembered", w.BOOL), ("authenticated", w.BOOL),
                ("seen", w.WORD * 8), ("used", w.WORD * 8), ("name", w.WCHAR * 248)]


class SearchParams(ctypes.Structure):
    _fields_ = [("size", w.DWORD), ("authenticated", w.BOOL),
                ("remembered", w.BOOL), ("unknown", w.BOOL),
                ("connected", w.BOOL), ("inquiry", w.BOOL),
                ("timeout", w.BYTE), ("radio", w.HANDLE)]


class BluetoothAddress(ctypes.Structure):
    # ws2bth.h uses one-byte packing for SOCKADDR_BTH.
    _pack_ = 1
    _fields_ = [("family", w.USHORT), ("address", ctypes.c_ulonglong),
                ("service", ctypes.c_ubyte * 16), ("port", w.ULONG)]


def paired_phones(saved_address=None):
    if sys.platform != "win32":
        return []
    api = ctypes.WinDLL("BluetoothApis.dll", use_last_error=True)
    api.BluetoothFindFirstDevice.argtypes = [ctypes.POINTER(SearchParams), ctypes.POINTER(DeviceInfo)]
    api.BluetoothFindFirstDevice.restype = w.HANDLE
    api.BluetoothFindNextDevice.argtypes = [w.HANDLE, ctypes.POINTER(DeviceInfo)]
    api.BluetoothFindNextDevice.restype = w.BOOL
    api.BluetoothFindDeviceClose.argtypes = [w.HANDLE]
    params = SearchParams(size=ctypes.sizeof(SearchParams), authenticated=True)
    info = DeviceInfo(size=ctypes.sizeof(DeviceInfo))
    handle = api.BluetoothFindFirstDevice(ctypes.byref(params), ctypes.byref(info))
    if not handle:
        return []
    devices = []
    try:
        while True:
            address = f"{info.address:012X}"
            if info.authenticated and ((info.device_class >> 8) & 31 == 2 or address == saved_address):
                devices.append({"address": address, "name": info.name})
            info = DeviceInfo(size=ctypes.sizeof(DeviceInfo))
            if not api.BluetoothFindNextDevice(handle, ctypes.byref(info)):
                break
    finally:
        api.BluetoothFindDeviceClose(handle)
    return sorted(devices, key=lambda item: item["address"] != saved_address)[:8]


def connect(address, timeout=5):
    if sys.platform != "win32":
        raise OSError("Bluetooth requires Windows")
    winsock = ctypes.WinDLL("Ws2_32.dll")
    winsock.connect.argtypes = [ctypes.c_size_t, ctypes.POINTER(BluetoothAddress), ctypes.c_int]
    winsock.connect.restype = ctypes.c_int
    winsock.WSAGetLastError.restype = ctypes.c_int
    remote = BluetoothAddress(family=32, address=int(address, 16), port=0)
    remote.service[:] = uuid.UUID(SERVICE_UUID).bytes_le
    channel = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
    channel.setblocking(False)
    try:
        result = winsock.connect(channel.fileno(), ctypes.byref(remote), ctypes.sizeof(remote))
        if result != 0:
            error = winsock.WSAGetLastError()
            if error not in (10035, 10036, 10037):
                raise OSError(error, "Bluetooth connection unavailable")
            _, writable, failed = select.select([], [channel], [channel], timeout)
            if not writable or failed or channel.getsockopt(socket.SOL_SOCKET, socket.SO_ERROR):
                raise TimeoutError("Bluetooth connection timed out")
        channel.settimeout(timeout)
        return channel
    except BaseException:
        channel.close()
        raise
