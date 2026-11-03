package vn.edu.p2p.peer.network;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RendezvousClientCancellationTest {
    @Test
    void cancelClosesAnUnresponsiveHandshakePromptlyAndIsIdempotent() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch socketClosed = new CountDownLatch(1);
        try (ServerSocket endpoint = new ServerSocket(0)) {
            Thread fakeTracker = new Thread(() -> {
                try (Socket socket = endpoint.accept()) {
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    input.readUTF();
                    input.readUTF();
                    input.readUTF();
                    requestReceived.countDown();
                    socket.getInputStream().read();
                } catch (Exception ignored) {
                    // Closing the client socket ends the deliberately unanswered handshake.
                } finally {
                    socketClosed.countDown();
                }
            }, "test-rendezvous-endpoint");
            fakeTracker.setDaemon(true);
            fakeTracker.start();

            RendezvousClient client = new RendezvousClient("127.0.0.1", endpoint.getLocalPort());
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<Socket> result = caller.submit(() -> client.accept("session", "token"));
                assertTrue(requestReceived.await(1, TimeUnit.SECONDS));
                client.cancelPendingHandshakes();
                client.cancelPendingHandshakes();

                ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                        ExecutionException.class, () -> result.get(500, TimeUnit.MILLISECONDS));
                assertTrue(failure.getCause().getMessage().contains("cancelled"));
                assertEquals(0, client.pendingHandshakeCount());
                assertTrue(socketClosed.await(1, TimeUnit.SECONDS));
            } finally {
                client.cancelPendingHandshakes();
                caller.shutdownNow();
                assertTrue(caller.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void closeAlsoCancelsAnInProgressProbeAndRejectsFutureWork() throws Exception {
            CountDownLatch probeReceived = new CountDownLatch(1);
            CountDownLatch socketClosed = new CountDownLatch(1);
            try (ServerSocket endpoint = new ServerSocket(0)) {
                Thread fakeTracker = new Thread(() -> {
                    try (Socket socket = endpoint.accept()) {
                        DataInputStream input = new DataInputStream(socket.getInputStream());
                        input.readUTF();
                        input.readUTF();
                        input.readInt();
                        probeReceived.countDown();
                        socket.getInputStream().read();
                    } catch (Exception ignored) {
                        // Closing the client socket ends the deliberately unanswered probe.
                    } finally {
                        socketClosed.countDown();
                    }
                }, "test-rendezvous-probe-endpoint");
                fakeTracker.setDaemon(true);
                fakeTracker.start();

                RendezvousClient client = new RendezvousClient("127.0.0.1", endpoint.getLocalPort());
                ExecutorService caller = Executors.newSingleThreadExecutor();
                try {
                    Future<RendezvousClient.Probe> result = caller.submit(() -> client.probe("session", 5001));
                    assertTrue(probeReceived.await(1, TimeUnit.SECONDS));
                    client.close();
                    client.close();

                    ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                            ExecutionException.class, () -> result.get(500, TimeUnit.MILLISECONDS));
                    assertTrue(failure.getCause().getMessage().contains("cancelled"));
                    assertTrue(socketClosed.await(1, TimeUnit.SECONDS));
                    org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                            () -> client.register("session"));
                } finally {
                    client.close();
                    caller.shutdownNow();
                    assertTrue(caller.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }
}
