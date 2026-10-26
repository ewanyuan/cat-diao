package com.ewan.wallpaperbridge;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Accept loopback or another host on the same RFC1918 subnet. */
public final class LanPolicy {
    private LanPolicy() { }

    public static boolean allows(InetAddress remote) {
        if (remote == null) return false;
        if (remote.isLoopbackAddress()) return true;
        if (!(remote instanceof Inet4Address)) return false;
        String ip = remote.getHostAddress();
        long value = parse(ip);
        if (!rfc1918(value)) return false;
        List<String> cidrs = localCidrs();
        if (cidrs.isEmpty()) return true;
        return sharesSubnet(ip, cidrs);
    }

    public static boolean sharesSubnet(String remoteIp, Iterable<String> cidrs) {
        long remote = parse(remoteIp);
        if (remote < 0 || !rfc1918(remote)) return false;
        if (cidrs == null) return false;
        for (String cidr : cidrs) {
            if (cidr == null) continue;
            int slash = cidr.indexOf('/');
            if (slash <= 0) continue;
            long local = parse(cidr.substring(0, slash));
            int prefix;
            try { prefix = Integer.parseInt(cidr.substring(slash + 1)); }
            catch (NumberFormatException error) { continue; }
            if (local < 0 || prefix < 0 || prefix > 32) continue;
            long mask = prefix == 0 ? 0L : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
            if ((remote & mask) == (local & mask)) return true;
        }
        return false;
    }

    static boolean rfc1918(long ip) {
        int first = (int) ((ip >> 24) & 0xff);
        int second = (int) ((ip >> 16) & 0xff);
        if (first == 10) return true;
        if (first == 192 && second == 168) return true;
        return first == 172 && second >= 16 && second <= 31;
    }

    static long parse(String ip) {
        if (ip == null) return -1;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return -1;
        long value = 0;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return -1;
            int octet;
            try { octet = Integer.parseInt(part); }
            catch (NumberFormatException error) { return -1; }
            if (octet < 0 || octet > 255) return -1;
            value = (value << 8) | octet;
        }
        return value;
    }

    public static List<String> localCidrs() {
        List<String> cidrs = new ArrayList<>();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    if (!(address.getAddress() instanceof Inet4Address)) continue;
                    String ip = address.getAddress().getHostAddress();
                    int prefix = address.getNetworkPrefixLength();
                    if (ip != null && rfc1918(parse(ip))) cidrs.add(ip + "/" + prefix);
                }
            }
        } catch (Exception ignored) { }
        return cidrs;
    }
}
