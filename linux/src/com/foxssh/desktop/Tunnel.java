package com.foxssh.desktop;

import java.util.ArrayList;
import java.util.List;

/** یک فوروارد پورت شبیه PuTTY: Local (-L)، Remote (-R) یا Dynamic (-D). قالب ذخیره: «L|8080|host:80». */
final class Tunnel {
    final char type;      // 'L' 'R' 'D'
    final int srcPort;
    final String dest;    // host:port (برای D خالی)

    Tunnel(char type, int srcPort, String dest) {
        this.type = type;
        this.srcPort = srcPort;
        this.dest = dest == null ? "" : dest;
    }

    String destHost() { return dest.substring(0, dest.lastIndexOf(':')); }
    int destPort() { return Integer.parseInt(dest.substring(dest.lastIndexOf(':') + 1)); }

    String encode() { return type + "|" + srcPort + "|" + dest; }

    @Override
    public String toString() {
        String t = type == 'L' ? "L" : type == 'R' ? "R" : "D";
        return type == 'D' ? t + String.valueOf(srcPort) : t + srcPort + "  →  " + dest;
    }

    /** null اگر نامعتبر. */
    static Tunnel parse(String s) {
        try {
            String[] p = s.split("\\|", -1);
            char t = p[0].charAt(0);
            int port = Integer.parseInt(p[1].trim());
            String d = p.length > 2 ? p[2].trim() : "";
            return make(t, port, d);
        } catch (Exception e) {
            return null;
        }
    }

    static Tunnel make(char t, int port, String dest) {
        if ((t != 'L' && t != 'R' && t != 'D') || port < 1 || port > 65535) return null;
        if (t != 'D') {
            int i = dest.lastIndexOf(':');
            if (i < 1 || i == dest.length() - 1) return null;
            try {
                int dp = Integer.parseInt(dest.substring(i + 1).trim());
                if (dp < 1 || dp > 65535) return null;
            } catch (NumberFormatException e) {
                return null;
            }
            if (dest.substring(0, i).trim().isEmpty() || dest.contains(";") || dest.contains("|")) return null;
        } else {
            dest = "";
        }
        return new Tunnel(t, port, dest);
    }

    static List<Tunnel> parseList(String s) {
        List<Tunnel> out = new ArrayList<>();
        if (s == null) return out;
        for (String part : s.split(";")) {
            if (part.trim().isEmpty()) continue;
            Tunnel t = parse(part);
            if (t != null) out.add(t);
        }
        return out;
    }

    static String encodeList(List<Tunnel> l) {
        StringBuilder sb = new StringBuilder();
        for (Tunnel t : l) {
            if (sb.length() > 0) sb.append(';');
            sb.append(t.encode());
        }
        return sb.toString();
    }
}
