package ir.darkdeveloper.bitkip.task;

import ir.darkdeveloper.bitkip.models.LinkModel;
import ir.darkdeveloper.bitkip.utils.DownloadUtils;
import javafx.concurrent.Task;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.List;

import static ir.darkdeveloper.bitkip.config.AppConfigs.log;

public class LinkDataTask extends Task<Flux<LinkModel>> {

    private final List<LinkModel> links;
    private boolean cancel;

    public LinkDataTask(List<LinkModel> links) {
        this.links = links;
    }

    @Override
    protected Flux<LinkModel> call() {
        return Flux.create(fluxSink -> {
            for (var lm : links) {
                if (cancel)
                    break;
                HttpURLConnection connection;
                try {
                    connection = DownloadUtils.connect(lm.getUrl(), true);
                } catch (IOException e) {
                    log.error(e.getMessage());
                    break;
                }
                var fileSize = DownloadUtils.getFileSize(connection);
                var fileName = DownloadUtils.extractFileName(lm.getUrl(), connection);
                lm.setName(fileName);
                lm.setSize(fileSize);
                lm.setResumable(DownloadUtils.canResume(connection));
                fluxSink.next(lm);
            }
            fluxSink.complete();
        });
    }

    public void setCancel(boolean cancel) {
        this.cancel = cancel;
    }
}
