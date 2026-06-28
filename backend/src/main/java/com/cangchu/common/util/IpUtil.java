package com.cangchu.common.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端真实 IP 解析（D-09 / G-6.2）。
 *
 * <p>反向代理/负载均衡环境下 {@code request.getRemoteAddr()} 取到的是代理节点 IP，
 * 真实客户端 IP 在 {@code X-Forwarded-For} 链首位。本工具按常见代理头优先级解析，
 * 取 XFF 第一个非 unknown 段作为客户端 IP。
 *
 * <p>注意：XFF 可被客户端伪造，因此 IP 维度仅作粗粒度防爆破闸门，
 * 精确风控（防重/日限）以手机号维度为准。
 */
public final class IpUtil {

    private IpUtil() {}

    private static final String UNKNOWN = "unknown";

    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String ip = firstValid(request.getHeader("X-Forwarded-For"));
        if (ip == null) ip = single(request.getHeader("X-Real-IP"));
        if (ip == null) ip = single(request.getHeader("Proxy-Client-IP"));
        if (ip == null) ip = single(request.getHeader("WL-Proxy-Client-IP"));
        if (ip == null) ip = request.getRemoteAddr();
        return ip;
    }

    /** 取 X-Forwarded-For 链首位有效 IP（client, proxy1, proxy2 ...） */
    private static String firstValid(String xff) {
        if (xff == null || xff.isBlank() || UNKNOWN.equalsIgnoreCase(xff)) {
            return null;
        }
        for (String part : xff.split(",")) {
            String candidate = part.trim();
            if (!candidate.isEmpty() && !UNKNOWN.equalsIgnoreCase(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String single(String value) {
        if (value == null || value.isBlank() || UNKNOWN.equalsIgnoreCase(value.trim())) {
            return null;
        }
        return value.trim();
    }
}
