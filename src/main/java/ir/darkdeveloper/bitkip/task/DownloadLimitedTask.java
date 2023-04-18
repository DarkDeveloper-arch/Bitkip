package ir.darkdeveloper.bitkip.task;

import ir.darkdeveloper.bitkip.controllers.DownloadingController;
import ir.darkdeveloper.bitkip.models.DownloadModel;
import ir.darkdeveloper.bitkip.models.DownloadStatus;
import ir.darkdeveloper.bitkip.repo.DownloadsRepo;
import ir.darkdeveloper.bitkip.utils.DownloadOpUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;

import static ir.darkdeveloper.bitkip.config.AppConfigs.*;
import static ir.darkdeveloper.bitkip.utils.DownloadOpUtils.openFile;


public class DownloadLimitedTask extends DownloadTask {
    private boolean paused;
    private boolean isCalculating;
    private final long limit;
    private final boolean isSpeedLimited;
    private File file;
    private ExecutorService executor;
    private FileChannel fileChannel;
    private int retries = 0;
    private boolean blocking;


    /**
     * if not isSpeedLimited, then valueLimit
     **/
    public DownloadLimitedTask(DownloadModel downloadModel, long limit, boolean isSpeedLimited) {
        super(downloadModel);
        this.limit = limit;
        this.isSpeedLimited = isSpeedLimited;
    }


    @Override
    protected Long call() throws IOException {
        file = new File(downloadModel.getFilePath());
        if (file.exists() && isCompleted(downloadModel, file, mainTableUtils))
            return 0L;
        try {
            performDownload();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            this.pause();
        }
        return getCurrentFileSize(file);
    }


    private void performDownload() throws IOException, InterruptedException {
        if (retries != downloadRetryCount) {
            try {
                var url = new URL(downloadModel.getUrl());
                var connection = (HttpURLConnection) url.openConnection();
                connection.setReadTimeout(3000);
                connection.setConnectTimeout(3000);
                configureResume(connection, file);
                var in = connection.getInputStream();
                var fileSize = downloadModel.getSize();

                var out = new FileOutputStream(file, file.exists());
                fileChannel = out.getChannel();

                var existingFileSize = 0L;
                if (file.exists())
                    existingFileSize = getCurrentFileSize(file);

                if (isSpeedLimited)
                    downloadSpeedLimited(fileChannel, in, file, limit, fileSize, existingFileSize);
                else {
                    calculateSpeedAndProgress(file, fileSize);
                    downloadValueLimited(fileChannel, in, limit, existingFileSize);
                }
            } catch (SocketTimeoutException | UnknownHostException | SocketException s) {
                retries++;
                if (!paused) {
                    Thread.sleep(2000);
                    performDownload();
                }
            } catch (ClosedChannelException ignore) {
            }
            var currFileSize = getCurrentFileSize(file);
            if (!paused && currFileSize != downloadModel.getSize())
                performDownload();
        }
        if (blocking && paused) {
            succeeded();
            mainTableUtils.updateDownloadProgress(downloadModel.getProgress(), downloadModel);
        }
        paused = true;
    }

    private void downloadSpeedLimited(FileChannel fileChannel, InputStream in,
                                      File file, long limit, long fileSize,
                                      long existingFileSize) throws IOException, InterruptedException {
        var byteChannel = Channels.newChannel(in);
        do {
            var beforeDown = System.currentTimeMillis();
            fileChannel.transferFrom(byteChannel, existingFileSize, limit);
            var afterDown = System.currentTimeMillis();

            var timeToWait = ONE_SEC;
            var downloadTime = afterDown - beforeDown;
            if (timeToWait >= downloadTime)
                timeToWait = ONE_SEC - downloadTime;
            else
                timeToWait = 0;
            Thread.sleep(timeToWait);
            var currentFileSize = getCurrentFileSize(file);
            existingFileSize = currentFileSize;
            updateProgress(currentFileSize, fileSize);
            updateValue(existingFileSize);
        } while (existingFileSize < fileSize && !paused);
    }

    private void downloadValueLimited(FileChannel fileChannel, InputStream in,
                                      long limit, long existingFileSize) throws IOException {
        var byteChannel = Channels.newChannel(in);
        fileChannel.transferFrom(byteChannel, existingFileSize, limit);
    }

    @Override
    protected void succeeded() {
        try {
            if (fileChannel != null)
                fileChannel.close();
            var dmOpt = currentDownloadings.stream()
                    .filter(c -> c.equals(downloadModel))
                    .findFirst();
            if (dmOpt.isPresent()) {
                var download = dmOpt.get();
                download.setDownloadStatus(DownloadStatus.Paused);
                downloadModel.setDownloadStatus(DownloadStatus.Paused);
                if (file.exists() && getCurrentFileSize(file) == downloadModel.getSize()) {
                    download.setCompleteDate(LocalDateTime.now());
                    download.setDownloadStatus(DownloadStatus.Completed);
                    downloadModel.setDownloadStatus(DownloadStatus.Completed);
                    download.setProgress(100);
                    download.setDownloaded(downloadModel.getSize());
                    updateProgress(1, 1);
                    DownloadsRepo.updateDownloadCompleteDate(download);
                    openDownloadings.stream().filter(dc -> dc.getDownloadModel().equals(download))
                            .findFirst().ifPresentOrElse(dc -> dc.onComplete(download),
                                    () -> {
                                        if (download.isShowCompleteDialog())
                                            DownloadOpUtils.openDownloadingStage(download);
                                    });
                    if (download.isOpenAfterComplete())
                        openFile(null, downloadModel);

                } else openDownloadings.stream().filter(dc -> dc.getDownloadModel().equals(download))
                        .forEach(DownloadingController::onPause);

                download.setDownloaded(getCurrentFileSize(file));
                DownloadsRepo.updateDownloadProgress(download);
                DownloadsRepo.updateDownloadLastTryDate(download);
                currentDownloadings.remove(download);
                mainTableUtils.refreshTable();
            }
            if (executor != null && !blocking)
                executor.shutdown();
            System.gc();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    protected void failed() {
        pause();
    }

    private void calculateSpeedAndProgress(File file, long fileSize) {
        if (isCalculating)
            return;
        executor.submit(() -> {
            try {
                isCalculating = true;
                while (!paused) {
                    Thread.sleep(ONE_SEC);
                    var currentFileSize = getCurrentFileSize(file);
                    updateProgress(currentFileSize, fileSize);
                    updateValue(currentFileSize);
                }
                isCalculating = false;
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        });
    }

    private void configureResume(HttpURLConnection connection, File file) throws IOException {
        if (file.exists()) {
            var existingFileSize = getCurrentFileSize(file);
            connection.addRequestProperty("Range", "bytes=" + existingFileSize + "-");
        }
    }


    @Override
    public void pause() {
        paused = true;
        succeeded();
    }

    @Override
    public void setExecutor(ExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public void setBlocking(boolean blocking) {
        this.blocking = blocking;
    }
}
