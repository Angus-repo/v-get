package tw.angus.clippocket;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VideoModels {
    private VideoModels() {}
    public record Format(String id, String label, String detail) {}
    public record VideoInfo(String id, String title, String uploader, double duration, String sourceUrl,
                            Instant expiresAt, List<Format> formats) {}
    record DownloadFormat(String id, String selector, int height, String detail) {}
    record StoredVideo(VideoInfo info, String owner, List<DownloadFormat> formats) {}
    public enum Status { QUEUED, RUNNING, READY, FAILED, CANCELLED }
    public record JobView(String id, Status status, String message, String fileUrl, String previewUrl, Instant expiresAt) {}
    static final class Job {
        final String id, owner, videoId, formatId;
        final Path directory;
        final String filename;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile Status status = Status.QUEUED;
        volatile String message = "已排入處理佇列";
        volatile Instant expiresAt;
        volatile Future<?> future;
        Job(String id, String owner, String videoId, String formatId, Path directory, String filename, Instant expiresAt) {
            this.id=id; this.owner=owner; this.videoId=videoId; this.formatId=formatId;
            this.directory=directory; this.filename=filename; this.expiresAt=expiresAt;
        }
        synchronized JobView view() {
            return new JobView(id,status,message,status==Status.READY?"/api/jobs/"+id+"/file":null,
                status==Status.READY?"/api/jobs/"+id+"/file?inline=true":null,expiresAt);
        }
    }
}
