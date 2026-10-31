package vn.edu.p2p.peer.auth;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import vn.edu.p2p.common.dto.HeartbeatResponse;
import vn.edu.p2p.peer.network.PeerSettings;
import vn.edu.p2p.peer.network.TrackerConnection;
import vn.edu.p2p.peer.session.ClientSession;
import vn.edu.p2p.peer.session.HeartbeatCoordinator;
import vn.edu.p2p.peer.session.HeartbeatListener;
import vn.edu.p2p.peer.session.PeerConnectionState;
import vn.edu.p2p.peer.transfer.PeerP2pSocketConfig;
import vn.edu.p2p.peer.transfer.TransferDashboardWindow;
import vn.edu.p2p.peer.share.SharedFilesStore;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LoginController {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    @FXML
    private TextField usernameField;

    @FXML
    private PasswordField passwordField;

    @FXML
    private TextField listeningPortField;

    @FXML
    private Button loginButton;

    @FXML
    private Button openDashboardButton;

    @FXML
    private Label statusLabel;

    @FXML
    private Label connectionStatusLabel;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "peer-auth-io");
        thread.setDaemon(true);
        return thread;
    });

    private AuthClientService authClientService;
    private HeartbeatCoordinator heartbeatCoordinator;
    private String initializationError;
    private TrackerConnection trackerConnection;
    private TransferDashboardWindow transferDashboard;

    @FXML
    private void initialize() {
        listeningPortField.setText("7001");
        openDashboardButton.setDisable(true);
        connectionStatusLabel.setText("Tracker: chưa đăng nhập");

        try {
            PeerSettings settings = PeerSettings.load();
            PeerIdentity identity = PeerIdentityStore.defaultStore().loadOrCreate();
            trackerConnection = new TrackerConnection(
                    settings.trackerConnectionConfig()
            );
            authClientService = new AuthClientService(trackerConnection, identity);

            heartbeatCoordinator = new HeartbeatCoordinator(
                    authClientService::heartbeat,
                    new UiHeartbeatListener()
            );
            statusLabel.setText(
                    "Tracker: " + settings.trackerHost() + ":" + settings.trackerPort()
            );
        } catch (Exception e) {
            initializationError = e.getMessage();
            loginButton.setDisable(true);
            statusLabel.setText("Không thể khởi tạo Peer: " + e.getMessage());
        }
    }

    @FXML
    private void onLogin() {
        if (initializationError != null || authClientService == null) {
            statusLabel.setText("Peer chưa sẵn sàng: " + initializationError);
            return;
        }

        String error = LoginFormValidator.validate(
                usernameField.getText(),
                passwordField.getText(),
                listeningPortField.getText()
        );
        if (error != null) {
            statusLabel.setText(error);
            return;
        }

        int listeningPort = Integer.parseInt(listeningPortField.getText().trim());
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        setBusy(true);
        statusLabel.setText("Đang kết nối Tracker...");
        connectionStatusLabel.setText("Tracker: CONNECTING");

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return authClientService.login(
                                username,
                                password,
                                listeningPort
                        );
                    } catch (AuthClientException e) {
                        throw new AuthOperationRuntimeException(e);
                    }
                }, ioExecutor)
                .whenComplete((session, throwable) -> Platform.runLater(() -> {
                    setBusy(false);

                    if (throwable != null) {
                        Throwable cause = unwrap(throwable);
                        statusLabel.setText("Đăng nhập thất bại: " + cause.getMessage());
                        connectionStatusLabel.setText("Tracker: OFFLINE");
                        return;
                    }

                    onLoginSuccess(session);
                }));
    }

    void onLogout() {
        if (authClientService == null || authClientService.currentSession() == null) {
            return;
        }

        ClientSession currentSession = authClientService.currentSession();
        if (heartbeatCoordinator != null) {
            heartbeatCoordinator.stop();
        }

        setBusy(true);
        statusLabel.setText("Đang đăng xuất...");

        CompletableFuture
                .runAsync(() -> {
                    try {
                        authClientService.logout();
                    } catch (AuthClientException e) {
                        throw new AuthOperationRuntimeException(e);
                    }
                }, ioExecutor)
                .whenComplete((ignored, throwable) -> Platform.runLater(() -> {
                    setBusy(false);

                    if (throwable != null) {
                        Throwable cause = unwrap(throwable);
                        statusLabel.setText("Đăng xuất thất bại: " + cause.getMessage());
                        if (heartbeatCoordinator != null
                                && authClientService.currentSession() != null) {
                            heartbeatCoordinator.start(currentSession);
                        }
                        return;
                    }

                    resetLoggedOutUi("Đã đăng xuất khỏi Tracker.");
                }));
    }

    private void onLoginSuccess(ClientSession session) {
        passwordField.clear();
        usernameField.setDisable(true);
        passwordField.setDisable(true);
        listeningPortField.setDisable(true);
        loginButton.setDisable(true);
        openDashboardButton.setDisable(false);
        statusLabel.setText(
                "Đăng nhập thành công. Peer=" + shortId(session.peerId())
                        + " | Session=" + shortId(session.sessionId())
        );
        connectionStatusLabel.setText("Tracker: ONLINE");

        if (heartbeatCoordinator != null) {
            heartbeatCoordinator.start(session);
        }

        SharedFilesStore.setCurrentUser(usernameField.getText().trim() + "-" + listeningPortField.getText().trim());

        openDashboardForSession(session);
    }

    @FXML
    private void onOpenDashboard() {
        if (authClientService != null && authClientService.currentSession() != null) {
            openDashboardForSession(authClientService.currentSession());
        }
    }

    private void openDashboardForSession(ClientSession session) {
        try {
            if (transferDashboard == null) {
                int port = Integer.parseInt(listeningPortField.getText().trim());
                transferDashboard = new TransferDashboardWindow(port, PeerP2pSocketConfig.fromEnvironment());
            }
            transferDashboard.show(trackerConnection, session.sessionId(), this::onLogout);
        } catch (Exception e) {
            closeTransferDashboard();
            statusLabel.setText("Peer Center cannot start (check upload port): " + e.getMessage());
        }
    }

    private void resetLoggedOutUi(String message) {
        if (heartbeatCoordinator != null) {
            heartbeatCoordinator.stop();
        }
        closeTransferDashboard();

        usernameField.setDisable(false);
        passwordField.setDisable(false);
        listeningPortField.setDisable(false);
        loginButton.setDisable(false);
        openDashboardButton.setDisable(true);
        statusLabel.setText(message);
        connectionStatusLabel.setText("Tracker: LOGGED OUT");
    }

    private void onSessionExpired(String message) {
        authClientService.invalidateLocalSession();
        resetLoggedOutUi(message);
    }

    private void closeTransferDashboard() {
        if (transferDashboard != null) {
            transferDashboard.close();
            transferDashboard = null;
        }
    }

    private void setBusy(boolean busy) {
        if (busy) {
            loginButton.setDisable(true);
        } else if (authClientService != null && authClientService.currentSession() != null) {
            loginButton.setDisable(true);
        } else {
            loginButton.setDisable(false);
        }
    }

    public void shutdown() {
        closeTransferDashboard();
        if (heartbeatCoordinator != null) {
            heartbeatCoordinator.close();
        }
        if (authClientService != null) {
            authClientService.closeGracefully();
        }
        ioExecutor.shutdownNow();
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof AuthOperationRuntimeException)) {
            current = current.getCause();
        }
        return current;
    }

    private static String shortId(String id) {
        return id == null || id.length() <= 8 ? id : id.substring(0, 8);
    }

    private final class UiHeartbeatListener implements HeartbeatListener {
        @Override
        public void onHeartbeat(HeartbeatResponse response) {
            Platform.runLater(() -> connectionStatusLabel.setText(
                    "Tracker: ONLINE • heartbeat "
                            + TIME_FORMAT.format(
                                    Instant.ofEpochMilli(response.serverTimeEpochMillis())
                            )
            ));
        }

        @Override
        public void onStateChanged(PeerConnectionState state, String message) {
            Platform.runLater(() -> {
                switch (state) {
                    case ONLINE -> {
                        if (!connectionStatusLabel.getText().startsWith("Tracker: ONLINE •")) {
                            connectionStatusLabel.setText("Tracker: ONLINE");
                        }
                    }
                    case CONNECTION_LOST ->
                            connectionStatusLabel.setText("Tracker: CONNECTION LOST");
                    case SESSION_EXPIRED -> onSessionExpired(message);
                    case LOGGED_OUT -> connectionStatusLabel.setText("Tracker: LOGGED OUT");
                }
            });
        }
    }

    private static final class AuthOperationRuntimeException extends RuntimeException {
        AuthOperationRuntimeException(Throwable cause) {
            super(cause);
        }
    }
}
