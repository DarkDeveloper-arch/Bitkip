package ir.darkdeveloper.bitkip.utils;

import ir.darkdeveloper.bitkip.config.AppConfigs;
import ir.darkdeveloper.bitkip.models.*;
import ir.darkdeveloper.bitkip.repo.DownloadsRepo;
import ir.darkdeveloper.bitkip.repo.QueuesRepo;
import ir.darkdeveloper.bitkip.repo.ScheduleRepo;
import javafx.application.Platform;
import javafx.scene.control.MenuItem;
import org.controlsfx.control.Notifications;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static ir.darkdeveloper.bitkip.config.AppConfigs.*;

public class QueueUtils {


    public static void startQueue(QueueModel qm, MenuItem startItem, MenuItem stopItem) {
        var schedule = qm.getSchedule();
        if (!startedQueues.contains(qm)) {
            var downloadsByQueue = new ArrayList<>(
                    QueuesRepo.findByName(qm.getName(), true)
                            .getDownloads()
                            .stream()
                            .sorted(Comparator.comparing(DownloadModel::getAddToQueueDate))
                            .toList()
            );
            if (downloadsByQueue.isEmpty()) {
                queueDoneNotification(qm);
                return;
            }
            startItem.setDisable(true);
            stopItem.setDisable(false);
            if (!qm.isDownloadFromTop())
                Collections.reverse(downloadsByQueue);
            qm.setDownloads(new CopyOnWriteArrayList<>(downloadsByQueue));
            startedQueues.add(qm);
            start(qm, startItem, stopItem);
        } else if (schedule.isEnabled() && schedule.isOnceDownload())
            currentSchedules.get(schedule.getId()).getStartScheduler().shutdown();

    }

    private static void start(QueueModel qm, MenuItem startItem, MenuItem stopItem) {
        var executor = Executors.newCachedThreadPool();
        executor.submit(() -> {
            var simulDownloads = new AtomicInteger(0);
            var sDownloads = qm.getSimultaneouslyDownload();
            var pauseCount = qm.getDownloads().stream().filter(dm -> dm.getDownloadStatus() == DownloadStatus.Paused).count();
            for (int i = 0; i < qm.getDownloads().size(); i++) {
                var dm = qm.getDownloads().get(i);
                if (dm.getDownloadStatus() == DownloadStatus.Paused) {
                    dm = mainTableUtils.getObservedDownload(dm);
                    if (!dm.getQueues().contains(qm))
                        dm.getQueues().add(qm);
                    String speedLimit = null;
                    if (qm.getSpeed() != null)
                        speedLimit = qm.getSpeed();
                    if (sDownloads > 1 && pauseCount >= sDownloads)
                        i = performSimultaneousDownloadWaitForPrev(qm, simulDownloads, i, dm, speedLimit, sDownloads);
                    else if (sDownloads > 1 && pauseCount < sDownloads) {
                        performSimultaneousDownloadDontWaitForPrev(pauseCount, simulDownloads, dm, speedLimit);
                    } else DownloadOpUtils.startDownload(dm, speedLimit,
                            null, true, true, null);
                }
                if (!startedQueues.contains(qm))
                    break;
            }

            // when queue is done or paused all manually and one simultaneously download
            if (sDownloads == 1 && startedQueues.contains(qm) && pauseCount != 0)
                whenQueueDone(qm, startItem, stopItem, executor);

            waitToFinishForLessPausedDownloads(qm, startItem, stopItem, executor, simulDownloads, pauseCount);

        });

    }


    /**
     * consider 7 files are going to download in parallel. 3 of them will get in if clause and the 4th one will be hold
     * in else clause until one of those 3 stops or finishes
     */
    private static int performSimultaneousDownloadWaitForPrev(QueueModel qm, AtomicInteger simulDownloads,
                                                              int i, DownloadModel dm, String speedLimit, int sDownloads) {
        if (simulDownloads.get() < sDownloads) {
            DownloadOpUtils.startDownload(dm, speedLimit,
                    null, true, false, null);
            simulDownloads.getAndIncrement();
        } else {
            while (true) {
                var count = currentDownloadings.stream().filter(d -> d.getQueues().contains(qm)).count();
                if (count < simulDownloads.get()) {
                    simulDownloads.set((int) count);
                    i--;
                    break;
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return i;
    }

    /**
     * it is useful when queue simultaneously downloads are greater than current paused downloads in queue
     * it starts all downloads non-blocking and the waiting to finish is done later in waitToFinishForLessPausedDownloads method
     *
     * @see QueueUtils#waitToFinishForLessPausedDownloads(QueueModel, MenuItem, MenuItem, ExecutorService, AtomicInteger, long)
     */
    private static void performSimultaneousDownloadDontWaitForPrev(long pauseCount, AtomicInteger simulDownloads,
                                                                   DownloadModel dm, String speedLimit) {
        if (simulDownloads.get() < pauseCount) {
            DownloadOpUtils.startDownload(dm, speedLimit, null, true, false, null);
            simulDownloads.getAndIncrement();
        }
    }


    /**
     * waits for non-blocking downloads to finish
     *
     * @see QueueUtils#performSimultaneousDownloadDontWaitForPrev(long, AtomicInteger, DownloadModel, String)
     */
    private static void waitToFinishForLessPausedDownloads(QueueModel qm, MenuItem startItem, MenuItem stopItem,
                                                           ExecutorService executor, AtomicInteger simulDownloads,
                                                           long pauseCount) {
        if (simulDownloads.get() == pauseCount) {
            var count = currentDownloadings.stream().filter(d -> d.getQueues().contains(qm)).count();
            while (count != 0) {
                try {
                    Thread.sleep(3000);
                    count = currentDownloadings.stream().filter(d -> d.getQueues().contains(qm)).count();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            whenQueueDone(qm, startItem, stopItem, executor);
        }
    }

    private static void whenQueueDone(QueueModel qm, MenuItem startItem, MenuItem stopItem, ExecutorService executor) {
        startItem.setDisable(false);
        stopItem.setDisable(true);
        queueDoneNotification(qm);
        startedQueues.remove(qm);
        shutdownSchedulersOnOnceDownload(qm);
        var schedule = qm.getSchedule();
        if (schedule.isEnabled() && schedule.isTurnOffEnabled())
            Platform.runLater(() -> FxUtils.newShuttingDownStage(schedule.getTurnOffMode()));
        if (executor != null)
            executor.shutdown();
    }


    public static void stopQueue(QueueModel qm, MenuItem startItem, MenuItem stopItem) {
        if (startedQueues.contains(qm)) {
            var downloadsByQueue = startedQueues.get(startedQueues.indexOf(qm)).getDownloads();
            downloadsByQueue.forEach(dm -> {
                dm = mainTableUtils.getObservedDownload(dm);
                DownloadOpUtils.pauseDownload(dm);
            });

            startedQueues.remove(qm);
            whenQueueDone(qm, startItem, stopItem, null);
        }
    }

    private static void shutdownSchedulersOnOnceDownload(QueueModel qm) {
        var schedule = qm.getSchedule();
        if (schedule.isEnabled() && schedule.isOnceDownload()) {
            currentSchedules.get(schedule.getId()).getStartScheduler().shutdown();
            var stopScheduler = currentSchedules.get(schedule.getId()).getStopScheduler();
            if (stopScheduler != null) stopScheduler.shutdownNow();
            schedule.setEnabled(false);
            var updatedQueues = getQueues().stream()
                    .peek(q -> {
                        if (q.equals(qm))
                            q.setSchedule(schedule);
                    }).toList();

            Platform.runLater(() -> addAllQueues(updatedQueues));
            ScheduleRepo.updateScheduleEnabled(schedule.getId(), schedule.isEnabled());
        }
    }

    private static void queueDoneNotification(QueueModel qm) {
        Runnable Notification = () -> Notifications.create()
                .title("Queue finished")
                .text("Queue %s finished or stopped".formatted(qm))
                .showInformation();

        if (Platform.isFxApplicationThread())
            Notification.run();
        else
            Platform.runLater(Notification);
    }

    public static void createFolders() {
        getQueues().stream().filter(QueueModel::hasFolder)
                .forEach(qm -> IOUtils.createFolderInSaveLocation("Queues" + File.separator + qm.getName()));
    }

    public static void deleteQueue(String name) {
        var content = "Are you sure you want to delete '" + name + "' queue?\nFiles are not deleted";
        var header = "Delete '" + name + "' ?";
        if (FxUtils.askWarning(header, content)) {
            moveFilesAndDeleteQueueFolder(name);
            QueuesRepo.deleteQueue(name);
            AppConfigs.deleteQueue(name);
        }
    }


    public static void moveFilesAndDeleteQueueFolder(String queueName) {
        var downloadsByQueueName = DownloadsRepo.getDownloadsByQueueName(queueName);
        downloadsByQueueName.forEach(dm -> {
            var newFilePath = FileType.determineFileType(dm.getName()).getPath() + dm.getName();
            DownloadOpUtils.moveFiles(dm, newFilePath);
        });
        IOUtils.removeFolder("Queues" + File.separator + queueName);
    }

}
