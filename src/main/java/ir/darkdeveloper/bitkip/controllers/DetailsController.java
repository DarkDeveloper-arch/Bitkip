package ir.darkdeveloper.bitkip.controllers;

import ir.darkdeveloper.bitkip.controllers.interfaces.FXMLController;
import ir.darkdeveloper.bitkip.models.DownloadModel;
import ir.darkdeveloper.bitkip.models.DownloadStatus;
import ir.darkdeveloper.bitkip.models.TurnOffMode;
import ir.darkdeveloper.bitkip.repo.DatabaseHelper;
import ir.darkdeveloper.bitkip.repo.DownloadsRepo;
import ir.darkdeveloper.bitkip.repo.QueuesRepo;
import ir.darkdeveloper.bitkip.task.DownloadTask;
import ir.darkdeveloper.bitkip.utils.DownloadOpUtils;
import ir.darkdeveloper.bitkip.utils.FxUtils;
import ir.darkdeveloper.bitkip.utils.IOUtils;
import ir.darkdeveloper.bitkip.utils.Validations;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.controlsfx.control.Notifications;
import org.controlsfx.control.PopOver;
import org.controlsfx.control.ToggleSwitch;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

import static ir.darkdeveloper.bitkip.config.AppConfigs.currentDownloadings;
import static ir.darkdeveloper.bitkip.config.AppConfigs.openDownloadings;
import static ir.darkdeveloper.bitkip.models.TurnOffMode.*;
import static ir.darkdeveloper.bitkip.repo.DatabaseHelper.DOWNLOADS_TABLE_NAME;
import static ir.darkdeveloper.bitkip.repo.DownloadsRepo.COL_TURNOFF_MODE;
import static ir.darkdeveloper.bitkip.utils.DownloadOpUtils.openFile;
import static ir.darkdeveloper.bitkip.utils.IOUtils.getBytesFromString;

public class DetailsController implements FXMLController {

    @FXML
    private Button speedApplyBtn;
    @FXML
    private ComboBox<TurnOffMode> turnOffCombo;
    @FXML
    private VBox container;
    @FXML
    private ScrollPane scrollPane;
    @FXML
    private Hyperlink link;
    @FXML
    private TextField bytesField, speedField;
    @FXML
    private ToggleSwitch openSwitch, showSwitch;
    @FXML
    private ProgressBar downloadProgress;
    @FXML
    private Label nameLbl, queueLbl, remainingLbl, locationLbl, progressLbl, resumableLbl, chunksLbl,
            statusLbl, speedLbl, downloadedOfLbl;
    @FXML
    private Button controlBtn, openFolderBtn;

    private Stage stage;
    private DownloadModel downloadModel;
    private boolean isComplete = false;
    private final BooleanProperty isPaused = new SimpleBooleanProperty(true);
    private final PopOver linkPopover = new PopOver();


    @Override
    public void initAfterStage() {
        updateTheme(stage.getScene());
        stage.heightProperty().addListener((o, ol, n) -> scrollPane.setPrefHeight(n.doubleValue()));
        stage.widthProperty().addListener((o, ol, n) -> {
            scrollPane.setPrefWidth(n.doubleValue());
            container.setPrefWidth(n.doubleValue() - 20);
        });
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

    public void setDownloadModel(DownloadModel downloadModel) {
        this.downloadModel = downloadModel;
        initDownloadData();
        initDownloadListeners();
    }

    private void initDownloadData() {
        Validations.validateInputChecks(null, bytesField, speedField, downloadModel);
        bytesField.setText(String.valueOf(downloadModel.getSize()));
        link.setText(downloadModel.getUrl());
        locationLbl.setText("Path: " + new File(downloadModel.getFilePath()).getParentFile().getAbsolutePath());
        var end = downloadModel.getName().length();
        if (end > 60)
            end = 60;
        stage.setTitle(downloadModel.getName().substring(0, end));
        nameLbl.setText("Name: " + downloadModel.getName());
        var queues = QueuesRepo.findQueuesOfADownload(downloadModel.getId()).toString();
        queueLbl.setText("Queues: " + queues.substring(1, queues.length() - 1));
        statusLbl.setText("Status: " + downloadModel.getDownloadStatus().name());
        var downloadOf = "%s / %s"
                .formatted(IOUtils.formatBytes(downloadModel.getDownloaded()),
                        IOUtils.formatBytes(downloadModel.getSize()));
        downloadedOfLbl.setText(downloadOf);
        progressLbl.setText("Progress: %.2f%%".formatted(downloadModel.getProgress()));
        downloadProgress.setProgress(downloadModel.getProgress() / 100);
        chunksLbl.setText("Chunks: " + downloadModel.getChunks());
        var resumable = downloadModel.isResumable();
        if (resumable) {
            resumableLbl.getStyleClass().add("yes");
            resumableLbl.getStyleClass().remove("no");
            resumableLbl.setText("Yes");
        } else {
            resumableLbl.getStyleClass().add("no");
            resumableLbl.getStyleClass().remove("yes");
            resumableLbl.setText("No");
        }
        controlBtn.setText(isPaused.get() ? (resumable ? "Resume" : "Restart") : "Pause");
        openSwitch.setSelected(downloadModel.isOpenAfterComplete());
        showSwitch.setSelected(downloadModel.isShowCompleteDialog());
        onComplete(downloadModel);
        turnOffCombo.getSelectionModel().select(downloadModel.getTurnOffMode());
    }

    public void initDownloadListeners() {
        var dt = getDownloadTask();
        if (dt != null) {
            isPaused.set(false);
            bytesDownloadedListener(dt);
            progressListener(dt);
        } else
            isPaused.set(true);
    }

    private DownloadTask getDownloadTask() {
        return currentDownloadings.stream()
                .filter(dm -> dm.equals(downloadModel))
                .findFirst()
                .map(DownloadModel::getDownloadTask).orElse(null);
    }

    private void bytesDownloadedListener(DownloadTask dt) {
        if (downloadModel.getSize() != -1)
            dt.valueProperty().addListener((ob, o, bytes) -> {
                if (!isPaused.get()) {
                    if (o == null)
                        o = bytes;
                    var speed = (bytes - o);
                    if (bytes == 0)
                        speed = 0;

                    downloadModel.setSpeed(speed);
                    downloadModel.setDownloadStatus(DownloadStatus.Downloading);
                    downloadModel.setDownloaded(bytes);

                    speedLbl.setText(IOUtils.formatBytes(speed));
                    statusLbl.setText("Status: " + DownloadStatus.Downloading);
                    var downloadOf = "%s / %s"
                            .formatted(IOUtils.formatBytes(bytes),
                                    IOUtils.formatBytes(downloadModel.getSize()));
                    downloadedOfLbl.setText(downloadOf);
                    if (speed > 0) {
                        long delta = downloadModel.getSize() - bytes;
                        var remaining = DurationFormatUtils.formatDuration((delta / speed) * 1000, "dd:HH:mm:ss");
                        remainingLbl.setText("Remaining: " + remaining);
                    }
                }
            });
        else
            dt.valueProperty().addListener((ob, o, bytes) -> {
                if (!isPaused.get()) {
                    downloadModel.setDownloadStatus(DownloadStatus.Downloading);
                    downloadModel.setDownloaded(bytes);
                    statusLbl.setText("Status: " + DownloadStatus.Downloading);
                    var downloadOf = "%s / %s".formatted(IOUtils.formatBytes(bytes), IOUtils.formatBytes(0));
                    downloadedOfLbl.setText(downloadOf);
                    remainingLbl.setText("Not Clear");
                }
            });
    }

    private void progressListener(DownloadTask dt) {
        dt.progressProperty().addListener((o, old, progress) -> {
            downloadProgress.setProgress(progress.floatValue());
            progressLbl.setText("Progress: %.2f%%".formatted(progress.floatValue() * 100));
        });
    }


    @Override
    public void initialize(URL location, ResourceBundle resources) {
        remainingLbl.setText("Remaining: Paused");
        isPaused.addListener((o, ol, newValue) -> {
            if (!Platform.isFxApplicationThread())
                Platform.runLater(() -> updatePause(newValue));
            else updatePause(newValue);
        });

        openSwitch.selectedProperty().addListener((o, old, newVal) -> {
            downloadModel.setOpenAfterComplete(newVal);
            DownloadsRepo.updateDownloadOpenAfterComplete(downloadModel);
        });
        showSwitch.selectedProperty().addListener((o, old, newVal) -> {
            downloadModel.setShowCompleteDialog(newVal);
            DownloadsRepo.updateDownloadShowCompleteDialog(downloadModel);
        });
        linkPopover.setAnimated(true);
        linkPopover.setContentNode(new Label("Copied"));
        link.setOnMouseExited(event -> linkPopover.hide());
        turnOffCombo.setItems(FXCollections.observableArrayList(NOTHING, SLEEP, TURN_OFF));
        speedApplyBtn.setVisible(false);
        speedApplyBtn.setDisable(true);
    }

    private void updatePause(Boolean paused) {
        if (downloadModel.getDownloadStatus() == DownloadStatus.Completed)
            return;
        controlBtn.setDisable(false);
        if (downloadModel.getChunks() == 0)
            bytesField.setDisable(!paused);
        // Todo: check if already downloaded some(first through db and filesystem)
        if (!speedField.getText().equals("0"))
            bytesField.setDisable(true);
        controlBtn.setText(paused ? (downloadModel.isResumable() ? "Resume" : "Restart") : "Pause");
        if (paused)
            remainingLbl.setText("Remaining: Paused");
        if (downloadModel.getDownloadStatus() == DownloadStatus.Merging) {
            setPauseButtonDisable(true);
            updateLabels("Status: " + DownloadStatus.Merging.name(), "Remaining: Merging");
            downloadProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        } else
            statusLbl.setText("Status: " + (paused ? DownloadStatus.Paused : DownloadStatus.Downloading));
        var downloadOf = "%s / %s"
                .formatted(IOUtils.formatBytes(downloadModel.getDownloaded()),
                        IOUtils.formatBytes(downloadModel.getSize()));
        downloadedOfLbl.setText(downloadOf);
        speedApplyBtn.setVisible(!paused);
        speedApplyBtn.setDisable(paused);

    }

    @FXML
    private void onClose() {
        closeStage();
    }

    @FXML
    private void onControl() {
        if (isComplete) {
            openFile(downloadModel);
            stage.close();
            return;
        }

        if (isPaused.get()) {
            statusLbl.setText("Status: " + DownloadStatus.Trying);
            controlBtn.setDisable(true);
            DownloadOpUtils.resumeDownloads(List.of(downloadModel),
                    getBytesFromString(speedField.getText()), Long.valueOf(bytesField.getText()));
            controlBtn.setDisable(false);
            isPaused.set(false);
        } else {
            var dt = getDownloadTask();
            if (dt != null) {
                dt.pause();
            }
            controlBtn.setDisable(true);
            isPaused.set(true);
        }

    }

    @FXML
    public void copyLink() {
        FxUtils.setClipboard(link.getText());
        linkPopover.show(link);
    }

    public void onPause() {
        Platform.runLater(() -> isPaused.set(true));
    }

    public void onComplete(DownloadModel download) {
        if (download.getDownloadStatus() == DownloadStatus.Completed)
            Platform.runLater(() -> {
                stage.show();
                stage.toFront();
                stage.setIconified(false);
                stage.setAlwaysOnTop(true);
                stage.setAlwaysOnTop(false);
                isComplete = true;
                remainingLbl.setText("Remaining: Done");
                controlBtn.setText("Open");
                openFolderBtn.setText("Open folder");
                downloadProgress.setProgress(100);
                statusLbl.setText("Status: Complete");
                progressLbl.setText("Progress: 100%");
                var downloadOf = "%s / %s"
                        .formatted(IOUtils.formatBytes(downloadModel.getSize()),
                                IOUtils.formatBytes(downloadModel.getSize()));
                downloadedOfLbl.setText(downloadOf);
                bytesField.setDisable(true);
                speedField.setDisable(true);
                openFolderBtn.setVisible(true);
                setPauseButtonDisable(false);
            });
    }

    public void closeStage() {
        openDownloadings.remove(this);
        linkPopover.hide();
        stage.close();
    }

    public DownloadModel getDownloadModel() {
        return downloadModel;
    }


    @FXML
    private void onFolderOpen() {
        DownloadOpUtils.openContainingFolder(downloadModel);
        stage.close();
    }

    @FXML
    private void onTurnOffChanged() {
        var value = turnOffCombo.getValue();
        downloadModel.setTurnOffMode(value);
        var downloadTask = downloadModel.getDownloadTask();
        if (downloadTask != null)
            downloadTask.setDownloadModel(downloadModel);
        DatabaseHelper.updateCol(COL_TURNOFF_MODE,
                value.toString(), DOWNLOADS_TABLE_NAME, downloadModel.getId());
        if (value != NOTHING) {
            Notifications.create()
                    .title("Turn off mode changed")
                    .text("Your computer will %s after download has done".formatted(value.toString().toLowerCase()))
                    .showInformation();
        }
    }

    @FXML
    private void onSpeedApplied() {
        var dmTask = downloadModel.getDownloadTask();
        if (dmTask != null) {
            long bytesFromString = getBytesFromString(speedField.getText());
            downloadModel.setSpeed(bytesFromString);
            dmTask.updateSpeed(bytesFromString);
        }
    }


    public ProgressBar getProgressBar() {
        return downloadProgress;
    }

    public void updateLabels(String status, String remaining) {
        statusLbl.setText(status);
        remainingLbl.setText(remaining);
    }

    public void setPauseButtonDisable(boolean disable) {
        controlBtn.setDisable(disable);
    }

    public Label getSpeedLbl() {
        return speedLbl;
    }

    public Label getDownloadedOfLbl() {
        return downloadedOfLbl;
    }
}
