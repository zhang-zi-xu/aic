package com.nongxin.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Immutable network-source snapshot for quota accounting, not a login identity.
 * Capture at the servlet boundary, never bind this value from a chat body or forwarding header.
 * Direct-connection mode requires server.forward-headers-strategy=none; trusted proxies are not enabled.
 */
public record QuotaClient(String address) {
    public QuotaClient {
        address = canonicalLiteral(address);
    }

    public boolean known() { return address != null; }

    /** Synchronous compatibility entry point; async callers must capture before dispatch. */
    public static QuotaClient captureCurrent() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return capture(attributes.getRequest());
        }
        return new QuotaClient(null);
    }

    public static QuotaClient capture(HttpServletRequest request) {
        // Neither the request object nor its headers escape into the worker's closure.
        if (request == null) return new QuotaClient(null);
        try {
            return new QuotaClient(request.getRemoteAddr());
        } catch (IllegalStateException e) {
            return new QuotaClient(null);
        }
    }

    private static String canonicalLiteral(String value) {
        if (value == null || value.isEmpty() || value.length() > 45) return null;
        if (!value.contains(":")) {
            String[] octets = value.split("\\.", -1);
            if (octets.length != 4) return null;
            for (String octet : octets) {
                if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) return null;
            }
            return value;
        }
        // Only literal IPv6 syntax reaches InetAddress: never resolve a hostname through DNS.
        // Ports, brackets, zone names, whitespace and comma-separated lists are not source literals.
        if (!value.matches("[0-9a-fA-F:.]+")) return null;
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
