package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.peer.network.RendezvousClient;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayAgentShutdownTest {
    @Test
    void closeCancelsAnAcceptorWaitingForRendezvousHandshake() throws Exception {
        CountDownLatch acceptHandshakeStarted = new CountDownLatch(1);
        CountDownLatch fakeEndpointStopped = new CountDownLatch(1);
        try (ServerSocket endpoint = new ServerSocket(0)) {
            Thread fakeTracker = new Thread(() -> {
                try (Socket control = endpoint.accept()) {
                    DataInputStream controlIn = new DataInputStream(control.getInputStream());
                    controlIn.readUTF();
                    controlIn.readUTF();
                    DataOutputStream controlOut = new DataOutputStream(control.getOutputStream());
                    controlOut.writeUTF("OK");
                    controlOut.writeUTF("INCOMING");
                    controlOut.writeUTF("test-token");
                    controlOut.flush();
                    try (Socket acceptor = endpoint.accept()) {
                        DataInputStream acceptIn = new DataInputStream(acceptor.getInputStream());
                        acceptIn.readUTF();
                        acceptIn.readUTF();
                        acceptIn.readUTF();
                        acceptHandshakeStarted.countDown();
                        acceptor.getInputStream().read();
                    }
                } catch (Exception ignored) {
                    // Closing the relay sockets is the expected test outcome.
                } finally {
                    fakeEndpointStopped.countDown();
                }
            }, "test-relay-endpoint");
            fakeTracker.setDaemon(true);
            fakeTracker.start();

            RendezvousClient client = new RendezvousClient("127.0.0.1", endpoint.getLocalPort());
            PeerUploadServer upload = new PeerUploadServer(0, new LocalFileRegistry(),
                    new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp()));
            RelayAgent agent = new RelayAgent(client, "test-session", upload);
            agent.start();
            try {
                assertTrue(acceptHandshakeStarted.await(2, TimeUnit.SECONDS),
                        "Relay acceptor did not start its handshake");
                agent.close();
                agent.close();
                assertTrue(agent.loopTerminated(), "Relay loop remained alive after close");
                assertTrue(agent.acceptorsTerminated(), "Relay acceptor pool remained alive after close");
                assertTrue(fakeEndpointStopped.await(1, TimeUnit.SECONDS),
                        "Fake endpoint did not observe closed relay sockets");
            } finally {
                agent.close();
                upload.close();
            }
        }
    }
}
