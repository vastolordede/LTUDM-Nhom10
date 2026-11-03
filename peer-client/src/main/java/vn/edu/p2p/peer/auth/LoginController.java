package vn.edu.p2p.peer.auth;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.stage.FileChooser;
import java.io.File;
import vn.edu.p2p.peer.network.TrackerPublicKeyInstaller;
import vn.edu.p2p.common.security.PeerAccessTickets;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import vn.edu.p2p.common.dto.HeartbeatResponse;
import vn.edu.p2p.common.config.EnvConfig;
import vn.edu.p2p.common.security.SecurityProfile;
import vn.edu.p2p.common.auth.RegisterInputPolicy;
import vn.edu.p2p.peer.network.PeerSettings;
import vn.edu.p2p.peer.network.UserTrackerSettingsStore;
import vn.edu.p2p.peer.network.TrackerConnectionDiagnostics;
import vn.edu.p2p.peer.network.TrackerConnectionConfig;
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
    private PasswordField confirmPasswordField;

    @FXML
    private Button registerButton;

    @FXML
    private TextField listeningPortField;

    @FXML private TextField trackerHostField;
    @FXML private TextField trackerPortField;
    @FXML private CheckBox trackerTlsCheckBox;
    @FXML private TextField trackerTrustStoreField;
    @FXML private Button saveTrackerButton;
    @FXML private Button diagnoseTrackerButton;
    @FXML private Button importTrackerKeyButton;

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
    private boolean logoutInProgress;
    private final UserTrackerSettingsStore trackerSettingsStore = UserTrackerSettingsStore.defaultStore();
    private PeerSettings trackerDefaults;
    private TrackerConnectionConfig activeTrackerConfig;

    @FXML
    private void initialize() {
        listeningPortField.setText("7001");
        openDashboardButton.setDisable(true);
        connectionStatusLabel.setText("Tracker: chưa đăng nhập");

        try {
            trackerDefaults = PeerSettings.load();
            UserTrackerSettingsStore.Endpoint saved = trackerSettingsStore.load(trackerDefaults);
            trackerHostField.setText(saved.host());
            trackerPortField.setText(Integer.toString(saved.port()));
            trackerTlsCheckBox.setSelected(saved.tls());
            trackerTrustStoreField.setText(saved.trustStorePath());
            applyTrackerConfig(saved);
            statusLabel.setText("Tracker: " + saved.host() + ":" + saved.port());
        } catch (Exception e) {
            initializationError = e.getMessage();
            loginButton.setDisable(true);
            registerButton.setDisable(true);
            statusLabel.setText("Không thể khởi tạo Peer: " + e.getMessage());
        }
    }

    @FXML
    private void onSaveTrackerSettings() {
        if (authClientService != null && authClientService.currentSession() != null) {
            statusLabel.setText("Please log out before changing Tracker settings.");
            return;
        }
        try {
            UserTrackerSettingsStore.Endpoint endpoint = new UserTrackerSettingsStore.Endpoint(
                    trackerHostField.getText().trim(),
                    Integer.parseInt(trackerPortField.getText().trim()),
                    trackerTlsCheckBox.isSelected(), trackerTrustStoreField.getText());
            // Validate all fields including TLS truststore configuration BEFORE persisting.
            endpoint.toConnectionConfig(trackerDefaults);
            trackerSettingsStore.save(endpoint);
            applyTrackerConfig(endpoint);
            statusLabel.setText("Tracker settings saved: " + endpoint.host() + ":" + endpoint.port());
        } catch (Exception e) {
            statusLabel.setText("Tracker config error: " + TrackerConnectionDiagnostics.explain(e));
        }
    }

    @FXML
    private void onImportTrackerPublicKey() {
        if (authClientService != null && authClientService.currentSession() != null) {
            statusLabel.setText("Please log out before switching Tracker trust settings.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Tracker PUBLIC key (never private)");
        File selected = chooser.showOpenDialog(importTrackerKeyButton.getScene().getWindow());
        if (selected == null) return;
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Verify Tracker identity");
        dialog.setHeaderText("Compare with fingerprint from Tracker administrator");
        dialog.setContentText("Trusted SHA-256 fingerprint:");
        dialog.showAndWait().ifPresent(expected -> {
            try {
                var installed = TrackerPublicKeyInstaller.install(selected.toPath(),
                        TrackerPublicKeyInstaller.defaultDestination(), expected);
                // Restart Peer app after key import so uploader reads the same trust material.
                statusLabel.setText("Public key fingerprint OK: " + installed
                        + ". Restart Peer Center/app before sharing.");
            } catch (Exception e) {
                statusLabel.setText("Key import rejected: " + e.getMessage());
            }
        });
    }

    @FXML
    private void onDiagnoseTracker() {
        TrackerConnectionConfig target;
        try {
            target = new UserTrackerSettingsStore.Endpoint(
                    trackerHostField.getText().trim(),
                    Integer.parseInt(trackerPortField.getText().trim()),
                    trackerTlsCheckBox.isSelected(), trackerTrustStoreField.getText())
                    .toConnectionConfig(trackerDefaults);
        } catch (Exception e) {
            statusLabel.setText("Invalid Tracker config: " + e.getMessage());
            return;
        }
        diagnoseTrackerButton.setDisable(true);
        statusLabel.setText("Testing Tracker TCP/TLS connection...");
        CompletableFuture.supplyAsync(() -> TrackerConnectionDiagnostics.probe(target), ioExecutor)
                .whenComplete((message, error) -> Platform.runLater(() -> {
                    diagnoseTrackerButton.setDisable(false);
                    statusLabel.setText(error == null ? message : TrackerConnectionDiagnostics.explain(error));
                }));
    }

    private void applyTrackerConfig(UserTrackerSettingsStore.Endpoint endpoint) throws Exception {
        TrackerConnectionConfig config = endpoint.toConnectionConfig(trackerDefaults);
        if (SecurityProfile.parse(EnvConfig.get("P2P_PROFILE", "development"))
                == SecurityProfile.Mode.PRODUCTION && !config.tlsEnabled()) {
            throw new IllegalStateException("Production Tracker connection requires TLS");
        }
        if (trackerConnection != null) trackerConnection.close();
        activeTrackerConfig = config;
        PeerIdentityStore identityStore = PeerIdentityStore.defaultStore();
        PeerIdentity identity = identityStore.loadOrCreate();
        trackerConnection = new TrackerConnection(config);
        authClientService = new AuthClientService(trackerConnection, identityStore, identity);
        heartbeatCoordinator = new HeartbeatCoordinator(authClientService::heartbeat,
                new UiHeartbeatListener(), authClientService.ticketRevocations());
        initializationError = null;
        loginButton.setDisable(false);
        registerButton.setDisable(false);
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
                        statusLabel.setText("Đăng nhập thất bại: "
                                + TrackerConnectionDiagnostics.explain(cause));
                        connectionStatusLabel.setText("Tracker: OFFLINE");
                        return;
                    }

                    onLoginSuccess(session);
                }));
    }

    @FXML
    private void onRegister() {
        if (authClientService == null || authClientService.currentSession() != null) {
            statusLabel.setText("Chỉ đăng ký khi chưa đăng nhập.");
            return;
        }
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        String password = passwordField.getText();
        String error = RegisterInputPolicy.validate(username, password);
        if (error == null && !password.equals(confirmPasswordField.getText())) {
            error = "Mật khẩu xác nhận không khớp.";
        }
        if (error != null) {
            statusLabel.setText(error);
            return;
        }
        setBusy(true);
        statusLabel.setText("Đang đăng ký tài khoản...");
        CompletableFuture.supplyAsync(() -> {
            try {
                return authClientService.register(username, password);
            } catch (AuthClientException e) {
                throw new AuthOperationRuntimeException(e);
            }
        }, ioExecutor).whenComplete((registered, throwable) -> Platform.runLater(() -> {
            setBusy(false);
            if (throwable != null) {
                statusLabel.setText("Đăng ký thất bại: " + unwrap(throwable).getMessage());
                return;
            }
            passwordField.clear();
            confirmPasswordField.clear();
            statusLabel.setText("Đã tạo tài khoản " + registered.username()
                    + ". Nhập mật khẩu để đăng nhập.");
        }));
    }

    private void onLogout() {
        if (logoutInProgress || authClientService == null || authClientService.currentSession() == null) {
            return;
        }
        logoutInProgress = true;
        if (transferDashboard != null) transferDashboard.setLogoutPending(true);

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
                    logoutInProgress = false;
                    if (transferDashboard != null) transferDashboard.setLogoutPending(false);
                    setBusy(false);

                    if (throwable != null) {
                        Throwable cause = unwrap(throwable);
                        statusLabel.setText("Đăng xuất thất bại: " + cause.getMessage());
                        if (transferDashboard != null) {
                            transferDashboard.showMessage("Không thể đăng xuất: " + cause.getMessage());
                        }
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
        confirmPasswordField.clear();
        registerButton.setDisable(true);
        usernameField.setDisable(true);
        passwordField.setDisable(true);
        listeningPortField.setDisable(true);
        trackerHostField.setDisable(true);
        trackerPortField.setDisable(true);
        trackerTlsCheckBox.setDisable(true);
        trackerTrustStoreField.setDisable(true);
        saveTrackerButton.setDisable(true);
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
                transferDashboard.setOnLogoutRequested(this::onLogout);
            }
            transferDashboard.show(trackerConnection, session.sessionId(), session.peerId(),
                    authClientService.ticketRevocations());
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
        trackerHostField.setDisable(false);
        trackerPortField.setDisable(false);
        trackerTlsCheckBox.setDisable(false);
        trackerTrustStoreField.setDisable(false);
        saveTrackerButton.setDisable(false);
        loginButton.setDisable(false);
        registerButton.setDisable(false);
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
        boolean loggedIn = authClientService != null && authClientService.currentSession() != null;
        loginButton.setDisable(busy || loggedIn || initializationError != null);
        registerButton.setDisable(busy || loggedIn || initializationError != null);
        openDashboardButton.setDisable(busy || !loggedIn);
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
