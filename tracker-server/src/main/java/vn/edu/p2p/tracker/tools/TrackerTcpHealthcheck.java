package vn.edu.p2p.tracker.tools;

import java.net.InetSocketAddress;
import java.net.Socket;

/** Small container liveness probe: TCP reachability only, not a full DB/protocol readiness test. */
public final class TrackerTcpHealthcheck {
    private TrackerTcpHealthcheck() { }
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9000;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1500);
        } catch (Exception e) {
            System.err.println("Tracker TCP listener not reachable: " + e.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
