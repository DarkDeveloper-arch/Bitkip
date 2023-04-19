package ir.darkdeveloper.bitkip;

import com.dustinredmond.fxtrayicon.FXTrayIcon;
import ir.darkdeveloper.bitkip.config.AppConfigs;
import ir.darkdeveloper.bitkip.repo.DownloadsRepo;
import ir.darkdeveloper.bitkip.repo.QueuesRepo;
import ir.darkdeveloper.bitkip.repo.ScheduleRepo;
import ir.darkdeveloper.bitkip.task.ScheduleTask;
import ir.darkdeveloper.bitkip.utils.*;
import javafx.application.Application;
import javafx.stage.Stage;

import java.net.URL;
import java.util.ArrayList;

import static ir.darkdeveloper.bitkip.config.AppConfigs.*;

public class BitKip extends Application {

    @Override
    public void start(Stage stage) {
        DownloadsRepo.createTable();
        ScheduleRepo.createSchedulesTable();
        QueuesRepo.createTable();
        var queues = QueuesRepo.getAllQueues(false, true);
        if (queues == null)
            queues = QueuesRepo.createDefaultRecords();
        queues = ScheduleRepo.createDefaultSchedulesForQueues(queues);
        AppConfigs.addAllQueues(queues);
        IOUtils.createSaveLocations();
        AppConfigs.setHostServices(getHostServices());
        FxUtils.startMainStage(stage);
        ScheduleTask.startSchedules();
        MoreUtils.checkUpdates(false);
        initTray(stage);
    }

    private void initTray(Stage stage) {
        var tray = new FXTrayIcon.Builder(stage, getResource("icons/logo.png"))
                .menuItem("Open App", e -> stage.show())
                .menuItem("Exit App", e -> {
                    stop();
                    System.exit(0);
                })
                .build();
        tray.show();
    }


    @Override
    public void stop() {
        var notObservedDms = new ArrayList<>(currentDownloadings);
        notObservedDms.forEach(dm -> dm.getDownloadTask().pause());
        startedQueues.clear();
        currentSchedules.values().forEach(sm -> {
            var startScheduler = sm.getStartScheduler();
            var stopScheduler = sm.getStopScheduler();
            if (startScheduler != null)
                startScheduler.shutdownNow();
            if (stopScheduler != null)
                stopScheduler.shutdownNow();
        });
    }


    public static void main(String[] args) {
        launch();
    }

    public static URL getResource(String path) {
        return BitKip.class.getResource(path);
    }
}