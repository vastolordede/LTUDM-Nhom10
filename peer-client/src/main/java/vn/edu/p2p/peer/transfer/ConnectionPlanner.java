package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.dto.FileSourceInfo;
import vn.edu.p2p.peer.network.RendezvousClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** D78-D80: thử kết nối trực tiếp, thất bại thì chuyển qua relay, báo chế độ kết nối. */
public final class ConnectionPlanner {
    public enum Mode { LAN, DIRECT, RELAY }

    private static final long RELAY_PREFERENCE_MS = 60_000;

    private final PeerP2pSocketFactory direct;
    private final RendezvousClient rendezvous; // null = relay tắt
    private final String sessionId;
    private final Map<String, String> peerByEndpoint = new ConcurrentHashMap<>();
    private final Map<String, Long> relayUntil = new ConcurrentHashMap<>();
    private final Map<String, Mode> lastMode = new ConcurrentHashMap<>();
    private volatile BiConsumer<String, Mode> listener;

    public ConnectionPlanner(PeerP2pSocketFactory direct, RendezvousClient rendezvous, String sessionId) {
        this.direct = direct;
        this.rendezvous = rendezvous;
        this.sessionId = sessionId;
    }

    public void setListener(BiConsumer<String, Mode> listener) {
        this.listener = listener;
    }

    /** Ghi nhớ peerId của từng endpoint để biết relay tới ai. */
    public void remember(List<FileSourceInfo> sources) {
        for (FileSourceInfo s : sources) {
            peerByEndpoint.put(s.ipAddress() + ":" + s.listeningPort(), s.peerId());
        }
    }

    public Socket open(String host, int port) throws IOException {
        String key = host + ":" + port;
        Long until = relayUntil.get(key);
        boolean skipDirect = until != null && until > System.currentTimeMillis();
        IOException failure = null;

        if (!skipDirect) {
            try {
                Socket socket = direct.openClientSocket(host, port);
                report(key, isPrivateAddress(host) ? Mode.LAN : Mode.DIRECT);
                return socket;
            } catch (IOException e) {
                failure = e;
            }
        }

        String peerId = peerByEndpoint.get(key);
        if (rendezvous != null && peerId != null) {
            try {
                Socket relay = rendezvous.connectViaRelay(sessionId, peerId, direct.readTimeoutMillis());
                Socket socket;
                try {
                    socket = direct.wrapClient(relay, host, port);
                } catch (IOException tlsFail) {
                    relay.close();
                    throw tlsFail;
                }
                relayUntil.put(key, System.currentTimeMillis() + RELAY_PREFERENCE_MS);
                report(key, Mode.RELAY);
                return socket;
            } catch (IOException e) {
                relayUntil.remove(key); // lần sau thử direct lại
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        throw failure != null ? failure : new IOException("No route to " + key);
    }

    private void report(String key, Mode mode) {
        Mode previous = lastMode.put(key, mode);
        BiConsumer<String, Mode> l = listener;
        if (l != null && previous != mode) {
            String peerId = peerByEndpoint.get(key);
            l.accept(peerId != null ? peerId : key, mode);
        }
    }

    public static boolean isPrivateAddress(String host) {
        try {
            InetAddress a = InetAddress.getByName(host);
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
