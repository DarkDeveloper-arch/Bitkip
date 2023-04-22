package ir.darkdeveloper.bitkip.controllers;

import ir.darkdeveloper.bitkip.controllers.interfaces.FXMLController;
import ir.darkdeveloper.bitkip.models.DownloadModel;
import ir.darkdeveloper.bitkip.models.DownloadStatus;
import ir.darkdeveloper.bitkip.repo.DownloadsRepo;
import ir.darkdeveloper.bitkip.repo.QueuesRepo;
import ir.darkdeveloper.bitkip.task.DownloadTask;
import ir.darkdeveloper.bitkip.utils.DownloadOpUtils;
import ir.darkdeveloper.bitkip.utils.IOUtils;
import ir.darkdeveloper.bitkip.utils.InputValidations;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Paint;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.controlsfx.control.Notifications;
import org.controlsfx.control.PopOver;
import org.controlsfx.control.ToggleSwitch;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

import static com.sun.jna.Platform.*;
import static ir.darkdeveloper.bitkip.BitKip.getResource;
import static ir.darkdeveloper.bitkip.config.AppConfigs.*;
import static ir.darkdeveloper.bitkip.utils.DownloadOpUtils.openFile;

public class DownloadingController implements FXMLController {

    @FXML
    private VBox container;
    @FXML
    private ScrollPane scrollPane;
    @FXML
    private Label locationLbl;
    @FXML
    private Button openFolderBtn;
    @FXML
    private Hyperlink link;
    @FXML
    private TextField speedField;
    @FXML
    private TextField bytesField;
    @FXML
    private ToggleSwitch openSwitch;
    @FXML
    private ToggleSwitch showSwitch;
    @FXML
    private Label progressLbl;
    @FXML
    private Label resumeableLbl;
    @FXML
    private ProgressBar downloadProgress;
    @FXML
    private Label nameLbl;
    @FXML
    private Label queueLbl;
    @FXML
    private Label statusLbl;
    @FXML
    private Label speedLbl;
    @FXML
    private Label downloadedOfLbl;
    @FXML
    private Label remainingLbl;
    @FXML
    private Button controlBtn;

    private Stage stage;
    private DownloadModel downloadModel;
    private boolean isComplete = false;
    private final BooleanProperty isPaused = new SimpleBooleanProperty(true);
    private final PopOver linkPopover = new PopOver();


    @Override
    public void initAfterStage() {
        var logoPath = getResource("icons/logo.png");
        if (logoPath != null) {
            var img = new Image(logoPath.toExternalForm());
            stage.getIcons().add(img);
        }

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
        InputValidations.validInputChecks(null, bytesField, speedField, downloadModel);
        bytesField.setText(downloadModel.getSize() + "");
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
        var resumeableText = new Text(downloadModel.isResumable() ? "Yes" : "No");
        resumeableText.setFill(downloadModel.isResumable() ? Paint.valueOf("#388E3C") : Paint.valueOf("#EF5350"));
        resumeableLbl.setGraphic(new HBox(new Text("Resumable: "), resumeableText));
        openSwitch.setSelected(downloadModel.isOpenAfterComplete());
        showSwitch.setSelected(downloadModel.isShowCompleteDialog());
        onComplete(downloadModel);
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
                .filter(c -> c.equals(downloadModel))
                .findFirst()
                .map(DownloadModel::getDownloadTask).orElse(null);
    }

    private void bytesDownloadedListener(DownloadTask dt) {
        dt.valueProperty().addListener((o, oldValue, bytesDownloaded) -> {
            if (!isPaused.get()) {
                if (oldValue == null)
                    oldValue = bytesDownloaded;
                var speed = (bytesDownloaded - oldValue);
                if (bytesDownloaded == 0)
                    speed = 0;

                downloadModel.setSpeed(speed);
                downloadModel.setDownloadStatus(DownloadStatus.Downloading);
                downloadModel.setDownloaded(bytesDownloaded);

                speedLbl.setText(IOUtils.formatBytes(speed));
                statusLbl.setText("Status: " + DownloadStatus.Downloading);
                var downloadOf = "%s / %s"
                        .formatted(IOUtils.formatBytes(bytesDownloaded),
                                IOUtils.formatBytes(downloadModel.getSize()));
                downloadedOfLbl.setText(downloadOf);
                if (speed != 0) {
                    long delta = downloadModel.getSize() - bytesDownloaded;
                    var remaining = DurationFormatUtils.formatDuration((delta / speed) * 1000, "dd:HH:mm:ss");
                    remainingLbl.setText("Remaining: " + remaining);
                }
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
        controlBtn.setText(isPaused.get() ? "Resume" : "Pause");
        remainingLbl.setText("Remaining: Paused");
        isPaused.addListener((o, ol, newValue) -> Platform.runLater(() -> {
            if (downloadModel.getDownloadStatus() == DownloadStatus.Completed)
                return;
            bytesField.setDisable(!newValue);
            speedField.setDisable(!newValue);
            if (!speedField.getText().equals("0"))
                bytesField.setDisable(true);
            controlBtn.setText(newValue ? "Resume" : "Pause");
            statusLbl.setText("Status: " + (newValue ? DownloadStatus.Paused : DownloadStatus.Downloading));
            var downloadOf = "%s / %s"
                    .formatted(IOUtils.formatBytes(downloadModel.getDownloaded()),
                            IOUtils.formatBytes(downloadModel.getSize()));
            downloadedOfLbl.setText(downloadOf);
            if (newValue)
                remainingLbl.setText("Remaining: Paused");
        }));

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
    }

    @FXML
    private void onClose() {
        closeStage();
    }

    @FXML
    private void onControl() {

        if (isComplete) {
            if (!new File(downloadModel.getFilePath()).exists()) {
                Notifications.create()
                        .title("File not found")
                        .text("File has been moved or removed")
                        .showError();
                return;
            }
            openFile(null, downloadModel);
            return;
        }

        if (isPaused.get()) {
            statusLbl.setText("Status: " + DownloadStatus.Trying);
            DownloadOpUtils.resumeDownloads(List.of(downloadModel),
                    speedField.getText(), bytesField.getText());
            isPaused.set(false);
        } else {
            var dt = getDownloadTask();
            if (dt != null)
                dt.pause();
            isPaused.set(true);
        }

    }

    @FXML
    public void copyLink() {
        var clip = Clipboard.getSystemClipboard();
        var content = new ClipboardContent();
        content.putString(link.getText());
        clip.setContent(content);
        linkPopover.show(link);
    }

    public void onPause() {
        Platform.runLater(() -> isPaused.set(true));
    }

    public void onComplete(DownloadModel download) {
        if (download.getDownloadStatus() == DownloadStatus.Completed) {
            stage.toFront();
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
        }
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
    private void onFolderOpen() throws IOException {
        var desktop = Desktop.getDesktop();
        File file = new File(downloadModel.getFilePath());
        if (desktop.isSupported(Desktop.Action.OPEN)) {
            if (isWindows())
                Runtime.getRuntime().exec(new String[]{"explorer", "/select,", file.getAbsolutePath()});
            else if (isLinux() || isSolaris() || isFreeBSD() || isOpenBSD())
                Runtime.getRuntime().exec(new String[]{"xdg-open", file.getParentFile().getAbsolutePath()});
            else if (isMac())
                Runtime.getRuntime().exec(new String[]{"osascript", "-e",
                        "tell app \"Finder\" to reveal POSIX file \"" + file.getAbsolutePath() + "\""});
        } else
            Notifications.create()
                    .title("Not Supported")
                    .text("Your operating system does not support this action")
                    .showError();

    }
}
