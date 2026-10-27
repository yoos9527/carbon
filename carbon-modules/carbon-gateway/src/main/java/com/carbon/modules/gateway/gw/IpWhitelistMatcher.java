package com.carbon.modules.gateway.gw;

import java.util.List;

/**
 * IP 白名单匹配（支持 CIDR）。
 *
 * <p>只支持 IPv4：连接器平台对内网三方接入，IPv6 场景几乎不存在，
 * 而把一个完整的 IPv6 前缀匹配器塞进来会让这段代码的复杂度翻倍——留一个明确的 {@code TODO} 比留一个半吊子实现好。
 *
 * @author carbon
 */
public final class IpWhitelistMatcher {

    private IpWhitelistMatcher() {
    }

    /**
     * @param ip    来源 IP，可能带 {@code /} 前缀或 IPv6 映射形式（如 {@code 0:0:0:0:0:0:0:1}）
     * @param cidrs 白名单，元素可以是单个 IP 或 {@code a.b.c.d/n}
     * @return {@code true} 命中任一条
     */
    public static boolean matches(String ip, List<String> cidrs) {
        if (cidrs == null || cidrs.isEmpty()) {
            return true;
        }
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String normalized = normalize(ip);
        for (String cidr : cidrs) {
            if (cidr == null || cidr.isBlank()) {
                continue;
            }
            if (matchesOne(normalized, cidr.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesOne(String ip, String cidr) {
        int slash = cidr.indexOf('/');
        String network = slash < 0 ? cidr : cidr.substring(0, slash);
        int bits = slash < 0 ? 32 : parseBits(cidr.substring(slash + 1));
        if (bits < 0) {
            return false;
        }
        Long ipValue = toLong(ip);
        Long netValue = toLong(network);
        if (ipValue == null || netValue == null) {
            return false;
        }
        long mask = bits == 0 ? 0L : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
        return (ipValue & mask) == (netValue & mask);
    }

    private static int parseBits(String text) {
        try {
            int bits = Integer.parseInt(text);
            return bits >= 0 && bits <= 32 ? bits : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String normalize(String ip) {
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            return "127.0.0.1";
        }
        return ip;
    }

    private static Long toLong(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        long value = 0L;
        for (String part : parts) {
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return null;
            }
            if (octet < 0 || octet > 255) {
                return null;
            }
            value = (value << 8) | octet;
        }
        return value;
    }
}
