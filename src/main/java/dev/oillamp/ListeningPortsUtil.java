package dev.oillamp;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import sprouts.Tuple;

/// Which TCP ports the agent listens on inside the sandbox, read from the text of the container's
/// `/proc/net/tcp` and `/proc/net/tcp6`. A pure function of that text.
///
/// Each line of those files after the header is one socket:
///
/// ```
///   sl  local_address rem_address   st tx_queue:rx_queue tr:tm->when retrnsmt   uid  timeout inode
///    0: 0100007F:1435 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 12345 …
/// ```
///
/// A port counts when its socket is listening (state `0A`), is bound to the loopback or to every
/// address, and belongs to the agent's uid. The uid is the one inside the container, because the
/// files are read there. The uid alone keeps out every bridge of the infra user (the proxy on
/// 3128, the model relay on 3129, the forwards) and anything else that is not the agent's.
///
/// Addresses are as the kernel prints them: each 32-bit word as hexadecimal in the machine's byte
/// order. That order is little-endian on every machine oillamp runs on (x86-64 and arm64), so
/// 127.0.0.1 reads `0100007F`.
final class ListeningPortsUtil {

    private ListeningPortsUtil() {}

    /// The state `/proc/net/tcp` gives a listening socket.
    private static final String LISTEN = "0A";

    private static final Pattern SPACES = Pattern.compile("\\s+");

    /// The ports `agentUid` listens on, on the loopback or every address, each once, in order.
    static Tuple<Integer> agentPorts(String procNetTcp, int agentUid) {
        Tuple<Integer> ports = Tuple.of(Integer.class);
        for (String line : procNetTcp.lines().toList()) {
            List<String> fields = SPACES.splitAsStream(line.strip()).toList();
            if (fields.size() < 8 || !fields.get(0).endsWith(":")) continue;  // the header, or noise
            if (!fields.get(3).equals(LISTEN) || !fields.get(7).equals(Integer.toString(agentUid))) continue;
            String local = fields.get(1);
            int colon = local.indexOf(':');
            if (colon < 0) continue;
            String address = local.substring(0, colon).toUpperCase(Locale.ROOT);
            if (!loopbackOrAny(address)) continue;
            int port;
            try {
                port = Integer.parseInt(local.substring(colon + 1), 16);
            } catch (NumberFormatException notAPort) {
                continue;
            }
            if (!ports.contains(port)) ports = ports.add(port);
        }
        return ports.sort(Integer::compare);
    }

    /// The ports among `listening` that may be offered for publishing: from 1024 up, and none of
    /// `excluded`, such as oillamp's own 3128 and 3129, the forwards, and those already decided.
    static Tuple<Integer> newPorts(Tuple<Integer> listening, Tuple<Integer> excluded) {
        return listening.retainIf(port -> port >= 1024 && port <= 65535 && !excluded.contains(port));
    }

    /// Whether an address, as `/proc/net/tcp` or `/proc/net/tcp6` prints it, is the loopback or
    /// every address: `127.0.0.0/8` and `0.0.0.0`, `::1` and `::`, and the first two written as
    /// IPv4-mapped IPv6 addresses.
    private static boolean loopbackOrAny(String address) {
        return switch (address.length()) {
            case 8 -> ipv4LoopbackOrAny(address);
            case 32 -> address.equals("0".repeat(32))
                    || address.equals("0".repeat(24) + "01000000")
                    || (address.startsWith("0000000000000000FFFF0000") && ipv4LoopbackOrAny(address.substring(24)));
            default -> false;
        };
    }

    /// Little-endian: the first octet of the address is the last byte of the word.
    private static boolean ipv4LoopbackOrAny(String word) {
        return word.equals("00000000") || word.endsWith("7F");
    }
}
