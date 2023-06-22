package ir.darkdeveloper.bitkip.controllers;

import ir.darkdeveloper.bitkip.config.AppConfigs;
import ir.darkdeveloper.bitkip.config.observers.QueueObserver;
import ir.darkdeveloper.bitkip.controllers.interfaces.FXMLController;
import ir.darkdeveloper.bitkip.task.FileMoveTask;
import ir.darkdeveloper.bitkip.utils.FxUtils;
import ir.darkdeveloper.bitkip.utils.IOUtils;
import ir.darkdeveloper.bitkip.utils.Validations;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Paint;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.controlsfx.control.Notifications;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.concurrent.Executors;

import static ir.darkdeveloper.bitkip.BitKip.getResource;
import static ir.darkdeveloper.bitkip.config.AppConfigs.*;
import static ir.darkdeveloper.bitkip.config.observers.QueueSubject.getQueueSubject;
import static ir.darkdeveloper.bitkip.config.observers.ThemeSubject.getThemeSubject;
import static ir.darkdeveloper.bitkip.config.observers.ThemeSubject.setTheme;

public class SettingsController implements FXMLController {

    @FXML
    private VBox root;
    @FXML
    private HBox actionArea;
    @FXML
    private TabPane tabPane;
    @FXML
    private VBox queueContainer;
    @FXML
    private CheckBox agentCheck;
    @FXML
    private Label agentDesc;
    @FXML
    private TextField agentField;
    @FXML
    private CheckBox continueCheck;
    @FXML
    private CheckBox completeDialogCheck;
    @FXML
    private Label lblLocation;
    @FXML
    private CheckBox serverCheck;
    @FXML
    private TextField portField;
    @FXML
    private TextField retryField;
    @FXML
    private TextField rateLimitField;
    @FXML
    private Label savedLabel;


    private Stage stage;
    private QueueObserver queueController;
    private VBox queueRoot;

    @Override
    public void initAfterStage() {
        updateTheme(stage.getScene());
        initQueues();
        stage.setTitle("Settings");
        stage.setResizable(true);
        stage.setOnCloseRequest(e -> {
            getThemeSubject().removeObserver(this);
            getQueueSubject().removeObserver(queueController);
        });
        var defaultStageWidth = stage.getMinWidth();
        var defaultStageHeight = stage.getMinHeight();
        tabPane.getSelectionModel().selectedIndexProperty().addListener((ob, o, n) -> {
            // if queue tab is selected
            if (n.intValue() == 2) {
                root.getChildren().remove(actionArea);
                var width = queueRoot.getPrefWidth() + 100;
                var height = queueRoot.getPrefHeight() + 100;
                stage.setWidth(width);
                stage.setHeight(height);
                stage.setMinWidth(width);
                stage.setMinHeight(height);
            } else if (!root.getChildren().contains(actionArea)){
                root.getChildren().add(actionArea);
                stage.setWidth(defaultStageWidth);
                stage.setHeight(defaultStageHeight);
                stage.setMinWidth(defaultStageWidth);
                stage.setMinHeight(defaultStageHeight);
                stage.setTitle("Settings");
            }
        });
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        Validations.validateIntInputCheck(portField, (long) serverPort);
        Validations.validateIntInputCheck(retryField, (long) downloadRetryCount);
        Validations.validateIntInputCheck(rateLimitField, (long) downloadRateLimitCount);
        agentDesc.setText("Note: If you enter wrong agent, your downloads may not start. Your agent will update when you use extension");
        initElements();
    }

    private void initElements() {
        lblLocation.setText(downloadPath);
        serverCheck.setSelected(serverEnabled);
        portField.setText(String.valueOf(serverPort));
        retryField.setText(String.valueOf(downloadRetryCount));
        rateLimitField.setText(String.valueOf(downloadRateLimitCount));
        completeDialogCheck.setSelected(showCompleteDialog);
        continueCheck.setSelected(continueOnLostConnectionLost);
        retryField.setDisable(continueOnLostConnectionLost);
        rateLimitField.setDisable(continueOnLostConnectionLost);
        agentField.setText(userAgent);
        agentCheck.setSelected(userAgentEnabled);
        agentField.setDisable(!userAgentEnabled);
    }

    private void initQueues() {
        try {
            var loader = new FXMLLoader(getResource("fxml/queueSetting.fxml"));
            queueRoot = loader.load();
            queueController = loader.getController();
            queueController.setStage(stage);
            queueContainer.getChildren().add(queueRoot);
            getQueueSubject().addObserver(queueController);
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new RuntimeException(e);
        }
    }


    @Override
    public void setStage(Stage stage) {
        this.stage = stage;
        initAfterStage();
    }

    @Override
    public Stage getStage() {
        return stage;
    }

    @FXML
    private void changeSaveDir(ActionEvent e) {
        var dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Select download location");
        dirChooser.setInitialDirectory(new File(downloadPath));
        var selectedDir = dirChooser.showDialog(FxUtils.getStageFromEvent(e));
        if (selectedDir != null) {
            var prevDownloadPath = downloadPath;
            downloadPath = selectedDir.getPath() + File.separator + "BitKip" + File.separator;
            IOUtils.createSaveLocations();
            IOUtils.saveConfigs();
            AppConfigs.initPaths();
            lblLocation.setText(downloadPath);
            var header = "Move downloaded files and folders?";
            var content = "Would you also like to move download files and folders to the new location?" +
                    " This might take some time to move files, some downloads that are saved outside BitKip folders, might not be accessed through the app";
            if (FxUtils.askWarning(header, content)) {
                try {
                    var size = IOUtils.getFolderSize(prevDownloadPath);
                    var executor = Executors.newCachedThreadPool();
                    var fileMoveTask = new FileMoveTask(prevDownloadPath, downloadPath, size, executor);
                    executor.submit(fileMoveTask);
                    FxUtils.fileTransferDialog(fileMoveTask);
                } catch (IOException ex) {
                    log.error("Failed to move files and folders: " + ex.getMessage());
                    Notifications.create()
                            .title("Failed to move")
                            .text("Failed to move files and folders")
                            .showError();
                }
            }

        }
    }

    @FXML
    private void onThemeChange(MouseEvent mouseEvent) {
        if (mouseEvent.getButton() != MouseButton.PRIMARY)
            return;
        if (theme.equals("light")) setTheme("dark");
        else setTheme("light");
        IOUtils.saveConfigs();
    }

    @FXML
    private void onServerCheck() {
        serverEnabled = serverCheck.isSelected();
        IOUtils.saveConfigs();
    }

    @FXML
    private void onCompleteDialogCheck() {
        showCompleteDialog = completeDialogCheck.isSelected();
        IOUtils.saveConfigs();
    }

    @FXML
    public void onSave() {
        serverPort = Integer.parseInt(portField.getText());
        downloadRetryCount = Integer.parseInt(retryField.getText());
        downloadRateLimitCount = Integer.parseInt(rateLimitField.getText());
        userAgent = agentField.getText();
        IOUtils.saveConfigs();
        showSavedMessage();
    }

    private void showSavedMessage() {
        var executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                Platform.runLater(() -> savedLabel.setText("Successfully Saved"));
                savedLabel.setTextFill(Paint.valueOf("#009688"));
                savedLabel.setVisible(true);
                Thread.sleep(2500);
                savedLabel.setVisible(false);
            } catch (InterruptedException ignore) {
            }
            executor.shutdown();
        });
    }

    @FXML
    private void onContinueCheck() {
        continueOnLostConnectionLost = continueCheck.isSelected();
        IOUtils.saveConfigs();
        retryField.setDisable(continueOnLostConnectionLost);
        rateLimitField.setDisable(continueOnLostConnectionLost);
    }

    @FXML
    private void onDefaults() {
        theme = defaultTheme;
        setTheme(theme);
        serverEnabled = defaultServerEnabled;
        serverPort = defaultServerPort;
        showCompleteDialog = defaultShowCompleteDialog;
        continueOnLostConnectionLost = defaultContinueOnLostConnectionLost;
        downloadRetryCount = defaultDownloadRetryCount;
        downloadRateLimitCount = defaultDownloadRateLimitCount;
        userAgent = defaultUserAgent;
        userAgentEnabled = defaultUserAgentEnabled;
        IOUtils.saveConfigs();
        initElements();
        showSavedMessage();
    }

    @FXML
    private void onAgentCheck() {
        userAgentEnabled = agentCheck.isSelected();
        IOUtils.saveConfigs();
        agentField.setDisable(!userAgentEnabled);
    }
}
