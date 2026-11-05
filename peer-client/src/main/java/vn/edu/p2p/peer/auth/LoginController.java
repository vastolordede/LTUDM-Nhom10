package vn.edu.p2p.peer.auth;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.stage.FileChooser;
import java.io.File;
import vn.edu.p2p.common.security.PeerAccessTickets;
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
import vn.edu.p2p.peer.transfer.ConnectionPlanner.ConnectivityStatusModel;
import vn.edu.p2p.peer.transfer.TransferDashboardWindow;
import vn.edu.p2p.peer.share.SharedFilesStore;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class LoginController {
    private static final Logger LOG = Logger.getLogger(LoginController.class.getName());
    private static final Duration TRACKER_LOGOUT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration APP_SHUTDOWN_TIMEOUT = Duration.ofSeconds(4);

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
    private final ConnectivityStatusModel connectivityStatus = new ConnectivityStatusModel();
    private ConnectivityStatusModel.Subscription connectivitySubscription;
    private boolean logoutInProgress;
    private boolean shuttingDown;
    private volatile long loggedInGeneration;
    private final UserTrackerSettingsStore trackerSettingsStore = UserTrackerSettingsStore.defaultStore();
    private PeerSettings trackerDefaults;
    private TrackerConnectionConfig activeTrackerConfig;

    @FXML
    private void initialize() {
        listeningPortField.setText("7001");
        openDashboardButton.setDisable(true);
        connectivitySubscription = connectivityStatus.subscribe(snapshot ->
                Platform.runLater(() -> {
                    if (!shuttingDown && connectionStatusLabel != null) {
                        connectionStatusLabel.setText(snapshot.trackerSummary());
                        connectionStatusLabel.setTooltip(snapshot.guidance().isBlank() ? null
                                : new Tooltip(snapshot.guidance()));
                    }
                }));
        connectivityStatus.tracker(ConnectivityStatusModel.TrackerState.LOGGED_OUT,
                "chưa đăng nhập");

        try {
            trackerDefaults = PeerSettings.load();
            UserTrackerSettingsStore.Endpoint saved = trackerSettingsStore.load(trackerDefaults);
            trackerHostField.setText(saved.host());
            trackerPortField.setText(Integer.toString(saved.port()));
            trackerTlsCheckBox.setSelected(saved.tls());
            trackerTrustStoreField.setText(saved.trustStorePath());
            applyTrackerConfig(saved);
            try {
                if (PeerAccessTickets.optionalPeerKeys().isEmpty()) {
                    statusLabel.setText("Chưa có khóa Tracker được tin cậy. Hãy nhập public key "
                            + "và đối chiếu fingerprint qua kênh tin cậy.");
                } else {
                    statusLabel.setText("Đã nạp cấu hình Tracker.");
                }
            } catch (RuntimeException e) {
                statusLabel.setText("Khóa Tracker chưa được tin cậy hoặc đã thay đổi. "
                        + "Hãy xác minh fingerprint và nhập lại khóa.");
            }
        } catch (Exception e) {
            initializationError = TrackerConnectionDiagnostics.explain(e, activeTrackerConfig);
            loginButton.setDisable(true);
            registerButton.setDisable(true);
            statusLabel.setText("Không thể khởi tạo Peer: " + initializationError);
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
            statusLabel.setText("Đã lưu cấu hình Tracker.");
        } catch (Exception e) {
            showDiagnostic(statusLabel,
                    TrackerConnectionDiagnostics.classify(e, activeTrackerConfig));
        }
    }

    @FXML
    private void onImportTrackerPublicKey() {
        if (authClientService != null && authClientService.currentSession() != null) {
            statusLabel.setText("Vui lòng đăng xuất trước khi thay đổi khóa Tracker được tin cậy.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn public key Tracker (không chọn private key)");
        File selected = chooser.showOpenDialog(importTrackerKeyButton.getScene().getWindow());
        if (selected == null) return;
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Xác minh Tracker");
        dialog.setHeaderText("Đối chiếu fingerprint do quản trị viên cung cấp qua kênh tin cậy");
        dialog.setContentText("Fingerprint SHA-256:");
        dialog.showAndWait().ifPresent(expected -> {
            try {
                TrackerPublicKeyInstaller.install(selected.toPath(),
                        TrackerPublicKeyInstaller.defaultDestination(), expected);
                showImportedFingerprint(expected);
            } catch (TrackerPublicKeyInstaller.ReplacementConfirmationRequiredException e) {
                TextInputDialog confirmation = new TextInputDialog();
                confirmation.setTitle("Xác nhận thay khóa Tracker");
                confirmation.setHeaderText("Khóa hiện tại khác khóa mới. Chỉ tiếp tục nếu đã "
                        + "xác minh fingerprint mới qua kênh tin cậy.");
                confirmation.setContentText("Nhập lại fingerprint SHA-256 mới:");
                confirmation.showAndWait().ifPresent(confirmed -> {
                    try {
                        TrackerPublicKeyInstaller.install(selected.toPath(),
                                TrackerPublicKeyInstaller.defaultDestination(), expected, confirmed);
                        showImportedFingerprint(expected);
                    } catch (Exception replacementError) {
                        statusLabel.setText("Không nhập được khóa Tracker: "
                                + replacementError.getMessage());
                    }
                });
            } catch (Exception e) {
                statusLabel.setText("Không nhập được khóa Tracker: " + e.getMessage());
            }
        });
    }

    private void showImportedFingerprint(String fingerprint) {
        statusLabel.setText("Đã tin cậy khóa Tracker. Fingerprint: "
                + PeerAccessTickets.formatFingerprint(
                        PeerAccessTickets.normalizeFingerprint(fingerprint))
                + ". Khởi động lại ứng dụng Peer để áp dụng.");
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
            showDiagnostic(statusLabel,
                    TrackerConnectionDiagnostics.classify(e, activeTrackerConfig));
            return;
        }
        diagnoseTrackerButton.setDisable(true);
        statusLabel.setText("Đang kiểm tra kết nối TCP/TLS tới Tracker...");
        long generation = AuthClientService.sessionGeneration();
        CompletableFuture.supplyAsync(
                        () -> TrackerConnectionDiagnostics.probeDiagnostic(target), ioExecutor)
                .whenComplete((diagnostic, error) -> {
                    postToCurrentUi(generation, () -> {
                        diagnoseTrackerButton.setDisable(false);
                        if (error == null) {
                            statusLabel.setText(diagnostic.message());
                            statusLabel.setTooltip(diagnostic.guidance().isBlank() ? null
                                    : new Tooltip(diagnostic.guidance()));
                        } else {
                            showDiagnostic(statusLabel,
                                    TrackerConnectionDiagnostics.classify(error, target));
                        }
                    });
                });
    }

    private void applyTrackerConfig(UserTrackerSettingsStore.Endpoint endpoint) throws Exception {
        TrackerConnectionConfig config = endpoint.toConnectionConfig(trackerDefaults);
        if (SecurityProfile.parse(EnvConfig.get("P2P_PROFILE", "development"))
                == SecurityProfile.Mode.PRODUCTION && !config.tlsEnabled()) {
            throw new IllegalStateException("Production Tracker connection requires TLS");
        }
        activeTrackerConfig = config;
        if (trackerConnection == null) {
            PeerIdentityStore identityStore = PeerIdentityStore.defaultStore();
            PeerIdentity identity = identityStore.loadOrCreate();
            trackerConnection = new TrackerConnection(config);
            authClientService = new AuthClientService(trackerConnection, identityStore, identity);
            heartbeatCoordinator = new HeartbeatCoordinator(authClientService::heartbeat,
                    new UiHeartbeatListener(), authClientService.ticketRevocations());
        } else {
            trackerConnection.reconfigure(config);
        }
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
        AuthClientService authService = authClientService;
        TrackerConnectionConfig attemptConfig = activeTrackerConfig;

        setBusy(true);
        statusLabel.setText("Đang kết nối Tracker...");
        connectivityStatus.tracker(ConnectivityStatusModel.TrackerState.CHECKING,
                "đang kết nối");
        long attemptGeneration = AuthClientService.sessionGeneration();

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return authService.login(
                                username,
                                password,
                                listeningPort
                        );
                    } catch (AuthClientException e) {
                        throw new AuthOperationRuntimeException(e);
                    }
                }, ioExecutor)
                .whenComplete((session, throwable) -> {
                    long resultGeneration = AuthClientService.sessionGeneration();
                    if (throwable == null) {
                        if (resultGeneration != attemptGeneration + 1
                                || authService.currentSession() != session) return;
                    } else if (resultGeneration != attemptGeneration) {
                        return;
                    }
                    postToCurrentUi(resultGeneration, () -> {
                        setBusy(false);

                        if (throwable != null) {
                            Throwable cause = unwrap(throwable);
                            logAuthFailure("login", cause, attemptConfig);
                            statusLabel.setText("Đăng nhập thất bại: "
                                    + safeAuthFailure(cause, attemptConfig, true));
                            var issue = TrackerConnectionDiagnostics.classify(cause, attemptConfig);
                            connectivityStatus.tracker(trackerFailureState(cause), issue,
                                    TrackerConnectionDiagnostics.diagnostic(issue).message());
                            return;
                        }

                        onLoginSuccess(session);
                    });
                });
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
        long attemptGeneration = AuthClientService.sessionGeneration();
        AuthClientService authService = authClientService;
        TrackerConnectionConfig attemptConfig = activeTrackerConfig;
        CompletableFuture.supplyAsync(() -> {
            try {
                return authService.register(username, password);
            } catch (AuthClientException e) {
                throw new AuthOperationRuntimeException(e);
            }
        }, ioExecutor).whenComplete((registered, throwable) -> {
            postToCurrentUi(attemptGeneration, () -> {
                setBusy(false);
                if (throwable != null) {
                    Throwable cause = unwrap(throwable);
                    logAuthFailure("registration", cause, attemptConfig);
                    statusLabel.setText("Đăng ký thất bại: "
                            + safeAuthFailure(cause, attemptConfig, false));
                    return;
                }
                passwordField.clear();
                confirmPasswordField.clear();
                statusLabel.setText("Đã tạo tài khoản " + registered.username()
                        + ". Nhập mật khẩu để đăng nhập.");
            });
        });
    }

    private void onLogout() {
        if (shuttingDown || logoutInProgress || authClientService == null
                || authClientService.currentSession() == null) {
            return;
        }
        logoutInProgress = true;
        ClientSession currentSession = beginLocalLogout();
        setBusy(true);
        statusLabel.setText("Đang đăng xuất...");

        CompletableFuture.supplyAsync(
                () -> authClientService.logoutBestEffort(currentSession, TRACKER_LOGOUT_TIMEOUT))
                .whenComplete((confirmed, error) -> {
                    long generation = AuthClientService.sessionGeneration();
                    postToCurrentUi(generation, () -> {
                        logoutInProgress = false;
                        resetLoggedOutUi(error == null && Boolean.TRUE.equals(confirmed)
                                ? "Đã đăng xuất khỏi Tracker."
                                : "Đã rời phiên; Tracker chưa xác nhận logout, phiên sẽ tự hết hạn.");
                    });
                });
    }

    private void onLoginSuccess(ClientSession session) {
        loggedInGeneration = AuthClientService.sessionGeneration();
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
        connectivityStatus.tracker(ConnectivityStatusModel.TrackerState.ONLINE,
                "đang hoạt động");

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
                transferDashboard = new TransferDashboardWindow(port,
                        PeerP2pSocketConfig.fromEnvironment(), connectivityStatus);
                transferDashboard.setOnLogoutRequested(this::onLogout);
            }
            transferDashboard.show(trackerConnection, session.sessionId(), session.peerId(),
                    authClientService.ticketRevocations());
        } catch (Exception e) {
            closeTransferDashboard();
            showDiagnostic(statusLabel, TrackerConnectionDiagnostics.classify(e));
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
        connectivityStatus.tracker(ConnectivityStatusModel.TrackerState.LOGGED_OUT,
                "đã đăng xuất");
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
        if (shuttingDown) return;
        shuttingDown = true;
        ConnectivityStatusModel.Subscription subscription = connectivitySubscription;
        connectivitySubscription = null;
        if (subscription != null) subscription.close();
        connectivityStatus.tracker(ConnectivityStatusModel.TrackerState.LOGGED_OUT,
                "ứng dụng đã đóng");
        long deadline = System.nanoTime() + APP_SHUTDOWN_TIMEOUT.toNanos();
        ClientSession session = beginLocalLogout();
        if (heartbeatCoordinator != null) {
            heartbeatCoordinator.close();
        }
        if (authClientService != null) {
            long remaining = Math.max(0L, deadline - System.nanoTime());
            if (session != null && remaining > 0) {
                authClientService.logoutBestEffort(session,
                        Duration.ofNanos(Math.min(TRACKER_LOGOUT_TIMEOUT.toNanos(), remaining)));
            }
            authClientService.close();
        }
        ioExecutor.shutdownNow();
    }

    private ClientSession beginLocalLogout() {
        ClientSession session = authClientService == null ? null : authClientService.currentSession();
        if (authClientService != null) {
            authClientService.invalidateLocalSession();
        }
        connectivityStatus.tracker(ConnectivityStatusModel.TrackerState.LOGGED_OUT,
                "đã đăng xuất");
        closeTransferDashboard();
        if (heartbeatCoordinator != null) {
            heartbeatCoordinator.stop();
        }
        return session;
    }

    private boolean isCurrentUiGeneration(long generation) {
        return !shuttingDown && AuthClientService.isCurrentGeneration(generation);
    }

    private void postToCurrentUi(long generation, Runnable action) {
        if (!isCurrentUiGeneration(generation)) return;
        Platform.runLater(() -> {
            if (isCurrentUiGeneration(generation)) action.run();
        });
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

    static void logAuthFailure(String operation, Throwable cause,
                               TrackerConnectionConfig attemptConfig) {
        LOG.log(Level.WARNING, "Tracker " + operation + " failed: "
                + TrackerConnectionDiagnostics.classify(cause, attemptConfig));
    }

    private static String safeAuthFailure(Throwable failure, TrackerConnectionConfig config,
                                          boolean login) {
        AuthClientException authFailure = null;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof AuthClientException authException) {
                authFailure = authException;
                break;
            }
        }
        if (authFailure == null || "NETWORK_ERROR".equals(authFailure.errorCode())) {
            return TrackerConnectionDiagnostics.explain(failure, config);
        }
        return switch (authFailure.errorCode()) {
            case "INVALID_CREDENTIALS" -> "Tên đăng nhập hoặc mật khẩu không đúng.";
            case "USERNAME_TAKEN" -> "Tên đăng nhập đã tồn tại.";
            case "FEATURE_UNAVAILABLE" -> "Tracker hiện chưa bật chức năng đăng ký.";
            case "LOCAL_KEY_ERROR", "LOCAL_IDENTITY_ERROR" ->
                    "Không thể đọc khóa hoặc danh tính Peer cục bộ. Kiểm tra quyền truy cập.";
            case "INVALID_RESPONSE" ->
                    "Tracker trả về phản hồi không hợp lệ. Kiểm tra phiên bản Peer và Tracker.";
            case "PEER_OWNERSHIP_MISMATCH" ->
                    "Danh tính Peer đang gắn với tài khoản khác. Kiểm tra tài khoản và cấu hình Peer.";
            default -> login
                    ? "Tracker không chấp nhận đăng nhập. Kiểm tra thông tin và thử lại."
                    : "Tracker không chấp nhận đăng ký. Kiểm tra thông tin và thử lại.";
        };
    }

    private static ConnectivityStatusModel.TrackerState trackerFailureState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof AuthClientException authFailure) {
                return switch (authFailure.errorCode()) {
                    case "NETWORK_ERROR" -> ConnectivityStatusModel.TrackerState.OFFLINE;
                    case "LOCAL_KEY_ERROR", "LOCAL_IDENTITY_ERROR" ->
                            ConnectivityStatusModel.TrackerState.CHECKING;
                    default -> ConnectivityStatusModel.TrackerState.ONLINE;
                };
            }

        }
        return ConnectivityStatusModel.TrackerState.OFFLINE;
    }

    private static void showDiagnostic(Label label,
                                       TrackerConnectionDiagnostics.FailureKind kind) {
        var diagnostic = TrackerConnectionDiagnostics.diagnostic(kind);
        label.setText(diagnostic.message());
        label.setTooltip(new Tooltip(diagnostic.guidance()));
    }

    private static String shortId(String id) {
        return id == null || id.length() <= 8 ? id : id.substring(0, 8);
    }

    private final class UiHeartbeatListener implements HeartbeatListener {
        @Override
        public void onHeartbeat(HeartbeatResponse response) {
            long generation = loggedInGeneration;
            postToCurrentUi(generation, () -> {
                connectivityStatus.tracker(ConnectivityStatusModel.TrackerState.ONLINE,
                        "đang hoạt động");
            });
        }

        @Override
        public void onStateChanged(PeerConnectionState state, String message) {
            long generation = loggedInGeneration;
            postToCurrentUi(generation, () -> {
                switch (state) {
                    case ONLINE -> {
                        connectivityStatus.tracker(ConnectivityStatusModel.TrackerState.ONLINE,
                                "đang hoạt động");
                    }
                    case CONNECTION_LOST -> connectivityStatus.tracker(
                            ConnectivityStatusModel.TrackerState.OFFLINE,
                            "mất kết nối; đang thử lại");
                    case SESSION_EXPIRED -> onSessionExpired(message);
                    case LOGGED_OUT -> connectivityStatus.tracker(
                            ConnectivityStatusModel.TrackerState.LOGGED_OUT, "đã đăng xuất");
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
