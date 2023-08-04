package ir.darkdeveloper.bitkip.task;


import ir.darkdeveloper.bitkip.models.DownloadModel;
import ir.darkdeveloper.bitkip.models.DownloadStatus;
import ir.darkdeveloper.bitkip.models.TurnOffMode;
import ir.darkdeveloper.bitkip.repo.DatabaseHelper;
import ir.darkdeveloper.bitkip.utils.FxUtils;
import ir.darkdeveloper.bitkip.utils.IOUtils;
import ir.darkdeveloper.bitkip.utils.MainTableUtils;
import ir.darkdeveloper.bitkip.utils.PowerUtils;
import javafx.application.Platform;
import javafx.concurrent.Task;

import java.io.File;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;

import static ir.darkdeveloper.bitkip.config.AppConfigs.*;
import static ir.darkdeveloper.bitkip.repo.DatabaseHelper.DOWNLOADS_TABLE_NAME;
import static ir.darkdeveloper.bitkip.repo.DownloadsRepo.*;

/**
 * Value returned by tasks is for transfer speed
 */
public abstract class DownloadTask extends Task<Long> {

    protected static final long ONE_SEC = 1000;
    protected DownloadModel downloadModel;

    public abstract void pause();

    public DownloadTask(DownloadModel downloadModel) {
        this.downloadModel = downloadModel;
    }


    protected boolean isCompleted(File file, MainTableUtils mainTableUtils) {
        try {
            var fs = downloadModel.getSize();
            var existingFileSize = IOUtils.getFileSize(file);
            if (fs != 0 && existingFileSize == fs) {
                var downloaded = downloadModel.getDownloaded();
                downloadModel.setDownloadStatus(DownloadStatus.Completed);
                downloadModel.setProgress(100);
                downloadModel.setDownloaded(fs);
                if (downloaded < fs) {
                    var cols = new String[]{COL_PROGRESS, COL_DOWNLOADED, COL_COMPLETE_DATE};
                    var vals = new String[]{
                            "100", String.valueOf(fs), LocalDateTime.now().toString()
                    };
                    DatabaseHelper.updateCols(cols, vals, DOWNLOADS_TABLE_NAME, downloadModel.getId());
                }
                mainTableUtils.refreshTable();
                currentDownloadings.stream()
                        .filter(c -> c.equals(downloadModel))
                        .findAny()
                        .ifPresent(currentDownloadings::remove);
                Platform.runLater(() -> FxUtils.newDetailsStage(downloadModel));
                return true;
            }
        } catch (Exception e) {
            log.error(e.getMessage());
        }
        return false;
    }

    public abstract void setExecutor(ExecutorService executor);

    public abstract boolean isPaused();

    public abstract void setBlocking(boolean blocking);

    public abstract void runBlocking();

    protected void whenDone() {
        var turnOffMode = downloadModel.getTurnOffMode();
        if (turnOffMode == TurnOffMode.NOTHING)
            return;

        Platform.runLater(() -> {
            log.info("Turn off triggered");
            if (FxUtils.askForShutdown(turnOffMode))
                PowerUtils.turnOff(turnOffMode);
        });
    }

    public void setDownloadModel(DownloadModel dm){
        downloadModel = dm;
    }

}