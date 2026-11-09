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
import java.nio.file.Files;
import java.nio.file.Path;
import vn.edu.p2p.common.security.PeerAccessTickets;
import vn.edu.p2p.peer.network.TrackerPublicKeyInstaller;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
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

    @FXML private VBox loginForm;
    @FXML private VBox registrationForm;
    @FXML private VBox advancedSettingsBox;
    @FXML private Label authModeLabel;
    @FXML private Button advancedSettingsToggleButton;
    @FXML private Button showRegisterButton;
    @FXML private Button showLoginButton;
    @FXML private TextField registerUsernameField;
    @FXML private PasswordField registerPasswordField;

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
        showAuthForm(false);
        setAdvancedSettingsVisible(false);
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
            updateSetupGuidance();
        } catch (Exception e) {
            initializationError = TrackerConnectionDiagnostics.explain(e, activeTrackerConfig);
            loginButton.setDisable(true);
            registerButton.setDisable(true);
            if (trackerDefaults != null) {
                updateSetupGuidance();
            } else {
                statusLabel.setText("Không thể khởi tạo Peer: " + initializationError);
            }
        }
    }

    /** Switch screens without sharing password/confirmation fields between forms. */
    private void showAuthForm(boolean registering) {
        loginForm.setVisible(!registering);
        loginForm.setManaged(!registering);
        registrationForm.setVisible(registering);
        registrationForm.setManaged(registering);
        authModeLabel.setText(registering ? "Tạo tài khoản mới" : "Đăng nhập vào Tracker");
        loginButton.setDefaultButton(!registering);
        registerButton.setDefaultButton(registering);
    }

    @FXML
    private void onShowRegisterForm() {
        if (authClientService != null && authClientService.currentSession() != null) return;
        if (registerUsernameField.getText().isBlank()) {
            registerUsernameField.setText(usernameField.getText());
        }
        passwordField.clear();
        registerPasswordField.clear();
        confirmPasswordField.clear();
        showAuthForm(true);
    }

    @FXML
    private void onShowLoginForm() {
        if (authClientService != null && authClientService.currentSession() != null) return;
        registerPasswordField.clear();
        confirmPasswordField.clear();
        showAuthForm(false);
    }

    private void setAdvancedSettingsVisible(boolean visible) {
        advancedSettingsBox.setVisible(visible);
        advancedSettingsBox.setManaged(visible);
        advancedSettingsToggleButton.setText(
                visible ? "Ẩn cấu hình nâng cao" : "Cấu hình nâng cao");
    }

    @FXML
    private void onToggleAdvancedSettings() {
        setAdvancedSettingsVisible(!advancedSettingsBox.isVisible());
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
            updateSetupGuidance();
        } catch (Exception e) {
            if (!trackerSettingsStore.hasSavedSettings()
                    || trackerTlsCheckBox.isSelected() && (trackerDefaults == null
                    || trackerDefaults.trackerTrustStorePassword() == null
                    || trackerDefaults.trackerTrustStorePassword().isBlank())) {
                updateSetupGuidance();
            } else {
                showDiagnostic(statusLabel,
                        TrackerConnectionDiagnostics.classify(e, activeTrackerConfig));
            }
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
                        statusLabel.setText("Không nhập được public key Tracker. "
                                + "Hãy chọn đúng tệp và nhập lại fingerprint đã xác minh.");
                    }
                });
            } catch (Exception e) {
                statusLabel.setText("Không nhập được public key Tracker. "
                        + "Hãy chọn đúng tệp và đối chiếu fingerprint qua kênh tin cậy.");
            }
        });
    }

    private void showImportedFingerprint(String fingerprint) {
        statusLabel.setText("Đã tin cậy khóa Tracker. Fingerprint: "
                + PeerAccessTickets.formatFingerprint(
                        PeerAccessTickets.normalizeFingerprint(fingerprint))
                + ". Khởi động lại ứng dụng Peer để áp dụng.");
    }

    private void updateSetupGuidance() {
        boolean trackerConfigured;
        try {
            new UserTrackerSettingsStore.Endpoint(trackerHostField.getText().trim(),
                    Integer.parseInt(trackerPortField.getText().trim()),
                    trackerTlsCheckBox.isSelected(), trackerTrustStoreField.getText());
            trackerConfigured = trackerSettingsStore.hasSavedSettings();
        } catch (RuntimeException e) {
            trackerConfigured = false;
        }
        try {
            trackerConfigured &= SecurityProfile.parse(
                    EnvConfig.get("P2P_PROFILE", "development"))
                    != SecurityProfile.Mode.PRODUCTION || trackerTlsCheckBox.isSelected();
        } catch (IllegalArgumentException e) {
            trackerConfigured = false;
        }

        boolean trustStoreReady = false;
        if (trackerTlsCheckBox.isSelected()) {
            try {
                String path = trackerTrustStoreField.getText().trim();
                trustStoreReady = !path.isBlank()
                        && trackerDefaults.trackerTrustStorePassword() != null
                        && !trackerDefaults.trackerTrustStorePassword().isBlank()
                        && Files.isRegularFile(Path.of(path))
                        && Files.isReadable(Path.of(path));
            } catch (RuntimeException e) {
                trustStoreReady = false;
            }
        }

        boolean publicKeyTrusted;
        boolean publicKeyValid = true;
        try {
            publicKeyTrusted = !PeerAccessTickets.optionalPeerKeys().isEmpty();
        } catch (RuntimeException e) {
            publicKeyTrusted = false;
            publicKeyValid = false;
        }

        SetupStep step = nextSetupStep(trackerConfigured,
                trackerTlsCheckBox.isSelected(), trustStoreReady,
                publicKeyTrusted, publicKeyValid);
        statusLabel.setText(setupGuidance(step));
        boolean ready = step == SetupStep.READY;
        loginButton.setDisable(!ready);
        registerButton.setDisable(!ready);
        // Show setup only when genuinely required; otherwise keep daily login focused.
        if (!ready) setAdvancedSettingsVisible(true);
        else setAdvancedSettingsVisible(false);
    }

    enum SetupStep {
        TRACKER_CONFIGURATION,
        TLS_TRUSTSTORE,
        PUBLIC_KEY,
        INVALID_PUBLIC_KEY,
        READY
    }

    static SetupStep nextSetupStep(boolean trackerConfigured, boolean tlsEnabled,
                                   boolean trustStoreReady, boolean publicKeyTrusted,
                                   boolean publicKeyValid) {
        if (!trackerConfigured) return SetupStep.TRACKER_CONFIGURATION;
        if (tlsEnabled && !trustStoreReady) return SetupStep.TLS_TRUSTSTORE;
        if (!publicKeyValid) return SetupStep.INVALID_PUBLIC_KEY;
        if (!publicKeyTrusted) return SetupStep.PUBLIC_KEY;
        return SetupStep.READY;
    }

    static String setupGuidance(SetupStep step) {
        return switch (step) {
            case TRACKER_CONFIGURATION -> "Bước 1/3: nhập địa chỉ/cổng Tracker, bật TLS theo "
                    + "chính sách Tracker nếu cần, rồi lưu cấu hình.";
            case TLS_TRUSTSTORE -> "Bước 2/3: chọn TLS, nhập đường dẫn truststore hợp lệ. "
                    + "Mật khẩu truststore phải được quản trị viên cấu hình bên ngoài ứng dụng.";
            case PUBLIC_KEY -> "Bước 3/3: nhập public key Tracker và đối chiếu fingerprint "
                    + "do quản trị viên cung cấp qua kênh tin cậy.";
            case INVALID_PUBLIC_KEY -> "Không đọc được public key Tracker đã tin cậy. "
                    + "Hãy nhập lại public key hợp lệ và đối chiếu fingerprint qua kênh tin cậy.";
            case READY -> "Cấu hình Tracker, TLS và public key đã sẵn sàng.";
        };
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
        String username = registerUsernameField.getText() == null
                ? "" : registerUsernameField.getText().trim();
        String password = registerPasswordField.getText();
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
                usernameField.setText(registered.username());
                registerPasswordField.clear();
                confirmPasswordField.clear();
                showAuthForm(false);
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
        registerPasswordField.clear();
        confirmPasswordField.clear();
        registerButton.setDisable(true);
        showRegisterButton.setDisable(true);
        showLoginButton.setDisable(true);
        advancedSettingsToggleButton.setDisable(true);
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

        SharedFilesStore.setCurrentUser(usernameField.getText().trim());

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
        registerUsernameField.setDisable(false);
        registerPasswordField.setDisable(false);
        showRegisterButton.setDisable(false);
        showLoginButton.setDisable(false);
        advancedSettingsToggleButton.setDisable(false);
        listeningPortField.setDisable(false);
        trackerHostField.setDisable(false);
        trackerPortField.setDisable(false);
        trackerTlsCheckBox.setDisable(false);
        trackerTrustStoreField.setDisable(false);
        saveTrackerButton.setDisable(false);
        loginButton.setDisable(false);
        registerButton.setDisable(false);
        openDashboardButton.setDisable(true);
        showAuthForm(false);
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
        showRegisterButton.setDisable(busy || loggedIn);
        showLoginButton.setDisable(busy || loggedIn);
        advancedSettingsToggleButton.setDisable(busy || loggedIn);
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
