package org.example.rpc.governance;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * IP 匹配：支持精确 IP 与 IPv4 CIDR（如 10.0.0.0/8）。
 */
public final class IpMatcher {

    private final List<Pattern> patterns;

    public IpMatcher(Collection<String> rules) {
        List<Pattern> parsed = new ArrayList<>();
        for (String rule : rules) {
            if (rule == null || rule.isBlank()) {
                continue;
            }
            String trimmed = rule.trim();
            int slash = trimmed.indexOf('/');
            if (slash > 0) {
                parsed.add(new Cidr(trimmed.substring(0, slash), parsePrefix(trimmed.substring(slash + 1))));
            } else {
                parsed.add(new ExactIp(trimmed));
            }
        }
        this.patterns = List.copyOf(parsed);
    }

    public boolean matches(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        for (Pattern pattern : patterns) {
            if (pattern.matches(ip)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return patterns.isEmpty();
    }

    private static int parsePrefix(String value) {
        try {
            return Math.max(0, Math.min(32, Integer.parseInt(value.trim())));
        } catch (NumberFormatException e) {
            return 32;
        }
    }

    private interface Pattern {
        boolean matches(String ip);
    }

    private static final class ExactIp implements Pattern {
        private final String expected;

        ExactIp(String expected) {
            this.expected = expected;
        }

        @Override
        public boolean matches(String ip) {
            return expected.equalsIgnoreCase(ip)
                    || normalize(ip).equals(normalize(expected));
        }

        private static String normalize(String ip) {
            try {
                return InetAddress.getByName(ip).getHostAddress();
            } catch (Exception e) {
                return ip;
            }
        }
    }

    private static final class Cidr implements Pattern {
        private final byte[] network;
        private final int prefix;

        Cidr(String network, int prefix) {
            this.network = parseIpv4(network);
            this.prefix = prefix;
        }

        @Override
        public boolean matches(String ip) {
            byte[] address = parseIpv4(ip);
            if (address == null || address.length != 4) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (network[i] != address[i]) {
                    return false;
                }
            }
            if (remainingBits > 0) {
                int mask = 0xFF << (8 - remainingBits);
                return (network[fullBytes] & mask) == (address[fullBytes] & mask);
            }
            return true;
        }

        private static byte[] parseIpv4(String value) {
            try {
                InetAddress address = InetAddress.getByName(value.trim());
                return address.getAddress();
            } catch (Exception e) {
                return null;
            }
        }
    }
}
