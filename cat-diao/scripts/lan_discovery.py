"""Physical LAN enumeration and bounded, concurrent IPv4 discovery scans."""

from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
import ipaddress
import json
import re
import socket
import subprocess
import sys
import threading
import time


PORT = 8767
MAX_PREFIX = 18
DEFAULT_SCAN_SECONDS = 20.0
DEFAULT_WORKERS = 256
CONNECT_TIMEOUT = 0.45
EXCLUDED_ADAPTER_MARKERS = (
    "vethernet", "wsl", "hyper-v", "tap", "tun", "wintun", "0dcloud",
    "tailscale", "vpn", "loopback", "bluetooth", "蓝牙",
)
EXCLUDED_NETWORKS = tuple(ipaddress.ip_network(value) for value in (
    "127.0.0.0/8", "169.254.0.0/16", "198.18.0.0/15",
))
LAN_NETWORKS = tuple(ipaddress.ip_network(value) for value in (
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
))

_CACHE_LOCK = threading.Lock()
_CACHE_AT = 0.0
_CACHE = []
_CACHE_SECONDS = 5.0


def _address_allowed(address):
    try:
        ip = ipaddress.ip_address(str(address))
    except ValueError:
        return False
    return ip.version == 4 and any(ip in net for net in LAN_NETWORKS) \
        and not any(ip in net for net in EXCLUDED_NETWORKS)


def _interface_record(value):
    if isinstance(value, dict):
        item = value
    else:
        try:
            address, mask = value
            item = {"address": address, "mask": mask, "hardware_interface": True}
        except (TypeError, ValueError):
            return None

    name = str(item.get("name", item.get("interface_alias", "")))
    description = str(item.get("description", item.get("interface_description", "")))
    searchable = (name + " " + description).casefold()
    if any(marker in searchable for marker in EXCLUDED_ADAPTER_MARKERS):
        return None
    if item.get("hardware_interface") is False:
        return None

    address = item.get("address", item.get("ip", item.get("ip_address", "")))
    if not _address_allowed(address):
        return None
    try:
        if "prefix_length" in item:
            prefix = int(item["prefix_length"])
        elif "prefix" in item:
            prefix = int(item["prefix"])
        elif "mask" in item:
            prefix = ipaddress.IPv4Network("0.0.0.0/" + str(item["mask"])).prefixlen
        else:
            prefix = 24
        if not 0 <= prefix <= 32:
            return None
        ipaddress.IPv4Network(f"{address}/{prefix}", strict=False)
    except (TypeError, ValueError):
        return None

    return {
        "address": str(ipaddress.IPv4Address(address)),
        "prefix_length": prefix,
        "name": name,
        "description": description,
        "default_gateway": bool(item.get("default_gateway", False)),
        "gateway_metric": int(item.get("gateway_metric", 1_000_000)),
    }


def eligible_interfaces(interfaces):
    """Return valid physical-LAN IPv4 records, with default routes first."""
    records = []
    seen = set()
    for value in interfaces or ():
        item = _interface_record(value)
        if item is None:
            continue
        key = (item["address"], item["prefix_length"])
        if key in seen:
            continue
        seen.add(key)
        records.append(item)
    return sorted(records, key=lambda item: (
        not item["default_gateway"], item["gateway_metric"], item["address"],
    ))


def _host_addresses(network):
    return (str(address) for address in network.hosts())


def build_scan_stages(interfaces):
    """Build deduplicated /24, /22, then whole-network scan tiers.

    Networks wider than /18 are clipped to the /18 containing each interface
    address, so a single tier never exceeds 16,382 usable IPv4 addresses.
    """
    records = eligible_interfaces(interfaces)
    stages = []
    seen = set()
    for prefix in (24, 22, None):
        stage = []
        for item in records:
            address = item["address"]
            if prefix is None:
                effective_prefix = max(item["prefix_length"], MAX_PREFIX)
            else:
                effective_prefix = prefix
            network = ipaddress.IPv4Network(f"{address}/{effective_prefix}", strict=False)
            for candidate in _host_addresses(network):
                if candidate == address or candidate in seen:
                    continue
                seen.add(candidate)
                stage.append(candidate)
        if stage:
            stages.append(stage)
    return stages


def scan_for_phone(interfaces, probe, deadline=None, on_progress=None,
                   max_workers=DEFAULT_WORKERS, clock=time.monotonic, sweep=None):
    """Probe staged addresses concurrently; probe(ip, timeout) must be read-only."""
    deadline = deadline if deadline is not None else clock() + DEFAULT_SCAN_SECONDS
    if on_progress:
        on_progress(0)
    checked = 0
    stages = build_scan_stages(interfaces)
    if sweep is not None and stages:
        # One ordered sweep over every tier, then probe every open port at once
        # with a /hello timeout long enough for a phone that is waking from idle
        # (measured ~3 s). Other LAN hosts with port 8767 open must not delay it.
        reserve = min(POST_SWEEP_RESERVE, max(0.0, (deadline - clock()) / 3))
        hits = list(sweep([ip for stage in stages for ip in stage], deadline=deadline - reserve))
        if on_progress:
            on_progress(len(hits))
        return _probe_hits(hits, probe, deadline, clock)
    executor = ThreadPoolExecutor(max_workers=max(1, int(max_workers)))
    should_wait_for_workers = True
    try:
        for stage in stages:
            if clock() >= deadline:
                should_wait_for_workers = False
                return None
            addresses = iter(stage)
            pending = {}
            exhausted = False
            while pending or not exhausted:
                while not exhausted and len(pending) < max(1, int(max_workers)) * 2:
                    remaining = deadline - clock()
                    if remaining <= 0:
                        should_wait_for_workers = False
                        return None
                    try:
                        address = next(addresses)
                    except StopIteration:
                        exhausted = True
                        break
                    timeout = min(CONNECT_TIMEOUT, remaining)
                    pending[executor.submit(probe, address, timeout)] = address
                if not pending:
                    break
                remaining = deadline - clock()
                if remaining <= 0:
                    should_wait_for_workers = False
                    return None
                completed, _ = wait(pending, timeout=min(0.05, remaining), return_when=FIRST_COMPLETED)
                if clock() >= deadline:
                    should_wait_for_workers = False
                    return None
                for future in completed:
                    address = pending.pop(future, None)
                    checked += 1
                    try:
                        found = future.result()
                    except Exception:
                        found = False
                    if on_progress and (checked % 256 == 0 or found):
                        on_progress(checked)
                    if found:
                        should_wait_for_workers = False
                        return address
            if clock() >= deadline:
                should_wait_for_workers = False
                return None
    finally:
        executor.shutdown(wait=should_wait_for_workers, cancel_futures=True)
        if on_progress and checked % 256:
            on_progress(checked)
    return None

POST_SWEEP_RESERVE = 6.0
POST_SWEEP_HELLO_TIMEOUT = 5.0


def _probe_hits(hits, probe, deadline, clock=time.monotonic):
    """Probe swept addresses concurrently; return the first (in input order) that answers."""
    if not hits:
        return None
    remaining = deadline - clock()
    if remaining <= 0:
        return None
    timeout = min(POST_SWEEP_HELLO_TIMEOUT, remaining)
    executor = ThreadPoolExecutor(max_workers=min(64, len(hits)))
    try:
        futures = {executor.submit(probe, ip, timeout): ip for ip in hits}
        pending = set(futures)
        while pending:
            remaining = deadline - clock()
            if remaining <= 0:
                return None
            done, pending = wait(pending, timeout=remaining, return_when=FIRST_COMPLETED)
            for future in done:
                try:
                    if future.result():
                        return futures[future]
                except Exception:
                    pass
        return None
    finally:
        executor.shutdown(wait=False, cancel_futures=True)


SWEEP_CONCURRENCY = 512  # measured on Ewan's WLAN: 2048+ makes Windows drop every connect
SWEEP_TIMEOUT = 0.4


def tcp_sweep(addresses, port=PORT, deadline=None, concurrency=SWEEP_CONCURRENCY,
              timeout=SWEEP_TIMEOUT, clock=time.monotonic):
    """Return addresses (in input order) that accept a TCP connect on port.

    asyncio keeps hundreds of half-open connects in flight, so a whole /18 takes
    ~15 s instead of the ~35 s a thread-per-HTTP-probe sweep needs.
    """
    import asyncio

    addresses = list(addresses)
    if not addresses:
        return []

    async def run():
        found = set()
        gate = asyncio.Semaphore(max(1, int(concurrency)))

        async def one(ip):
            async with gate:
                if deadline is not None and clock() >= deadline:
                    return
                wait_for = timeout if deadline is None else max(0.05, min(timeout, deadline - clock()))
                try:
                    _, writer = await asyncio.wait_for(asyncio.open_connection(ip, port), wait_for)
                except Exception:
                    return
                found.add(ip)
                try:
                    writer.close()
                except Exception:
                    pass

        await asyncio.gather(*(one(ip) for ip in addresses))
        return found

    loop = asyncio.new_event_loop()
    try:
        found = loop.run_until_complete(run())
    finally:
        loop.close()
    return [ip for ip in addresses if ip in found]


def _powershell_interfaces(deadline=None):
    if sys.platform != "win32":
        return []
    script = r'''
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$adapters = @(Get-NetAdapter -Physical -ErrorAction Stop)
$adapterMap = @{}
foreach ($adapter in $adapters) { $adapterMap[[int]$adapter.ifIndex] = $adapter }
$routes = @(Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue)
$routeMap = @{}
foreach ($route in $routes) {
    $index = [int]$route.InterfaceIndex
    $metric = [int]$route.RouteMetric + [int]$route.InterfaceMetric
    if (!$routeMap.ContainsKey($index) -or $metric -lt $routeMap[$index]) { $routeMap[$index] = $metric }
}
$items = @(
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
    Where-Object { $_.AddressState -eq 'Preferred' -and $adapterMap.ContainsKey([int]$_.InterfaceIndex) } |
    ForEach-Object {
        $adapter = $adapterMap[[int]$_.InterfaceIndex]
        $index = [int]$_.InterfaceIndex
        $hasRoute = $routeMap.ContainsKey($index)
        [PSCustomObject]@{
            address = $_.IPAddress
            prefix_length = [int]$_.PrefixLength
            interface_index = $index
            name = $adapter.Name
            description = $adapter.InterfaceDescription
            hardware_interface = [bool]$adapter.HardwareInterface
            default_gateway = $hasRoute
            gateway_metric = $(if ($hasRoute) { [int]$routeMap[$index] } else { 1000000 })
        }
    }
)
ConvertTo-Json -InputObject @($items) -Compress -Depth 3
'''
    timeout = 3.0
    if deadline is not None:
        timeout = min(timeout, max(0.1, deadline - time.monotonic()))
    command = ["powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script]
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    try:
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace",
                                timeout=timeout, check=False, creationflags=flags)
        if result.returncode != 0 or not result.stdout.strip():
            return []
        decoded = json.loads(result.stdout)
        if isinstance(decoded, dict):
            decoded = [decoded]
        return eligible_interfaces(decoded if isinstance(decoded, list) else [])
    except (OSError, subprocess.SubprocessError, ValueError, TypeError, json.JSONDecodeError):
        return []


def _wmi_interfaces(deadline=None):
    """Fallback physical-adapter lookup for systems where NetAdapter cmdlets fail."""
    if sys.platform != "win32":
        return []
    script = r'''
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$physical = @(Get-CimInstance Win32_NetworkAdapter -Filter 'NetEnabled = TRUE' -ErrorAction Stop |
    Where-Object { $_.PhysicalAdapter -eq $true })
$configs = @(Get-CimInstance Win32_NetworkAdapterConfiguration -Filter 'IPEnabled = TRUE' -ErrorAction Stop)
$items = @(
    foreach ($config in $configs) {
        $adapter = $physical | Where-Object {
            ($_.InterfaceIndex -eq $config.InterfaceIndex) -or ($_.Index -eq $config.Index)
        } | Select-Object -First 1
        if (!$adapter) { continue }
        for ($i = 0; $i -lt $config.IPAddress.Count; $i++) {
            $address = [string]$config.IPAddress[$i]
            if ($address -notmatch '^\d{1,3}(\.\d{1,3}){3}$') { continue }
            $mask = if ($i -lt $config.IPSubnet.Count) { [string]$config.IPSubnet[$i] } else { '255.255.255.0' }
            $hasGateway = @($config.DefaultIPGateway | Where-Object { $_ }).Count -gt 0
            [PSCustomObject]@{
                address = $address
                mask = $mask
                name = $adapter.NetConnectionID
                description = $adapter.Name
                hardware_interface = $true
                default_gateway = $hasGateway
            }
        }
    }
)
ConvertTo-Json -InputObject @($items) -Compress -Depth 3
'''
    timeout = 3.0
    if deadline is not None:
        timeout = min(timeout, max(0.1, deadline - time.monotonic()))
    command = ["powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script]
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    try:
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace",
                                timeout=timeout, check=False, creationflags=flags)
        if result.returncode != 0 or not result.stdout.strip():
            return []
        decoded = json.loads(result.stdout)
        if isinstance(decoded, dict):
            decoded = [decoded]
        return eligible_interfaces(decoded if isinstance(decoded, list) else [])
    except (OSError, subprocess.SubprocessError, ValueError, TypeError, json.JSONDecodeError):
        return []


def _parse_ipconfig_text(output):
    """Extract only IPv4 addresses from named Ethernet/WLAN adapter blocks."""
    records = []
    current = None
    prefixes = (
        ("wireless lan adapter ", "wifi"),
        ("wireless adapter ", "wifi"),
        ("wlan adapter ", "wifi"),
        ("ethernet adapter ", "ethernet"),
        ("无线局域网适配器 ", "wifi"),
        ("以太网适配器 ", "ethernet"),
    )
    ipv4_pattern = re.compile(r"(?<!\d)(?:\d{1,3}\.){3}\d{1,3}(?!\d)")
    for line in str(output).splitlines():
        header = line.strip()
        folded = header.casefold()
        selected = next(((prefix, kind) for prefix, kind in prefixes if folded.startswith(prefix)), None)
        if selected:
            prefix, _kind = selected
            name = header[len(prefix):].rstrip(": ").strip()
            current = {"name": name, "description": name, "hardware_interface": True,
                       "default_gateway": False}
            records.append(current)
            continue
        if header.endswith(":") and ("adapter" in folded or "适配器" in header):
            current = None
            continue
        if current is None:
            continue
        if "ipv4" in folded:
            addresses = ipv4_pattern.findall(line)
            if addresses:
                current["address"] = addresses[-1]
        elif "subnet mask" in folded or "子网掩码" in line:
            masks = ipv4_pattern.findall(line)
            if masks:
                current["mask"] = masks[-1]
        elif "default gateway" in folded or "默认网关" in line:
            gateways = [value for value in ipv4_pattern.findall(line) if value != "0.0.0.0"]
            current["default_gateway"] = bool(gateways)
    return eligible_interfaces(records)


def _ipconfig_interfaces(deadline=None):
    if sys.platform != "win32":
        return []
    timeout = 2.5
    if deadline is not None:
        timeout = min(timeout, max(0.1, deadline - time.monotonic()))
    try:
        result = subprocess.run(["ipconfig"], capture_output=True, text=True, encoding="mbcs", errors="replace",
                                timeout=timeout, check=False,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        if result.returncode != 0:
            return []
        return _parse_ipconfig_text(result.stdout)
    except (OSError, subprocess.SubprocessError, LookupError):
        return []


def _route_selected_interface():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # UDP connect selects a route and local source address without sending data.
        sock.connect(("8.8.8.8", 80))
        address = sock.getsockname()[0]
    except OSError:
        return None
    finally:
        sock.close()
    if not _address_allowed(address):
        return None
    return {"address": address, "prefix_length": 24, "name": "route-selected",
            "hardware_interface": True, "default_gateway": True, "gateway_metric": 0}


def get_lan_interfaces(deadline=None, refresh=False):
    """Enumerate physical Windows interfaces, with route, ipconfig, and WMI fallbacks."""
    global _CACHE_AT, _CACHE
    now = time.monotonic()
    with _CACHE_LOCK:
        if not refresh and _CACHE and now - _CACHE_AT < _CACHE_SECONDS:
            return [dict(item) for item in _CACHE]
        found = _powershell_interfaces(deadline)
        if not found:
            route = _route_selected_interface()
            physical = _ipconfig_interfaces(deadline)
            if not physical:
                physical = _wmi_interfaces(deadline)
            if route and physical:
                route_address = route["address"]
                matched = [item for item in physical if item["address"] == route_address]
                if matched:
                    matched[0]["default_gateway"] = True
                    matched[0]["gateway_metric"] = 0
                    found = eligible_interfaces(matched + [item for item in physical if item not in matched])
                else:
                    found = physical
            elif physical:
                found = physical
            else:
                found = eligible_interfaces([route]) if route else []
        _CACHE = found
        _CACHE_AT = time.monotonic()
        return [dict(item) for item in found]
