package tw.angus.clippocket;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import static tw.angus.clippocket.VideoModels.*;

@Service
public class VideoService {
    private static final Logger log=LoggerFactory.getLogger(VideoService.class);
    private final FacebookUrlPolicy urlPolicy;
    private final FacebookLinkResolver linkResolver;
    private final YtDlpGateway gateway;
    private final VideoProperties properties;
    private final Path root;
    private final Map<String,StoredVideo> videos=new ConcurrentHashMap<>();
    private final Map<String,Job> jobs=new ConcurrentHashMap<>();
    private final Semaphore inspections=new Semaphore(1);
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),
        r->{Thread t=new Thread(r,"video-download");t.setDaemon(false);return t;},new ThreadPoolExecutor.AbortPolicy());
    public VideoService(FacebookUrlPolicy urlPolicy,FacebookLinkResolver linkResolver,YtDlpGateway gateway,VideoProperties properties) throws IOException {
        this.urlPolicy=urlPolicy;this.linkResolver=linkResolver;this.gateway=gateway;this.properties=properties;
        Path parent=properties.workDir().toAbsolutePath().normalize();Files.createDirectories(parent);
        // Dedicated instance directory; never delete a caller-specified directory recursively.
        root=Files.createTempDirectory(parent,"instance-");
    }
    public VideoInfo inspect(String input,String owner) {
        String url=urlPolicy.validate(input);
        if(!inspections.tryAcquire())throw ApiException.busy();
        Path directory=null;
        try {
            cleanup();if(videos.size()>=properties.maxMetadata())throw ApiException.busy();
            directory=Files.createTempDirectory(root,"inspect-");
            url=linkResolver.resolve(url);
            JsonNode info=gateway.inspect(url,directory);List<DownloadFormat> formats=gateway.formats(info);
            String id=UUID.randomUUID().toString();
            String title=cleanTitle(info.path("title").asText("Facebook 影片"));
            List<Format> visible=formats.stream().map(f->new Format(f.id(),f.height()>0?f.height()+"p "+(f.height()>=720?"高畫質":"標準畫質"):
                f.id().equals("qhd")?"HD（來源標示）":"原始畫質",f.detail())).toList();
            VideoInfo video=new VideoInfo(id,title,cleanTitle(info.path("uploader").asText("Facebook")),
                info.path("duration").asDouble(0),url,Instant.now().plus(properties.metadataTtl()),visible);
            videos.put(id,new StoredVideo(video,owner,formats));return video;
        }catch(IOException e){throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE,"STORAGE_UNAVAILABLE","暫存空間無法使用，請聯絡管理員。");}
        finally {if(directory!=null)deleteTree(directory);inspections.release();}
    }
    public synchronized JobView createJob(String videoId,String formatId,String owner) {
        cleanup();StoredVideo video=videos.get(videoId);
        if(video==null || !video.owner().equals(owner) || video.info().expiresAt().isBefore(Instant.now()))throw ApiException.missing();
        DownloadFormat format=video.formats().stream().filter(f->f.id().equals(formatId)).findFirst().orElseThrow(()->ApiException.invalid("請選擇影片實際提供的畫質。"));
        Optional<Job> existing=jobs.values().stream().filter(j->j.owner.equals(owner)&&j.videoId.equals(videoId)&&j.formatId.equals(formatId)
            &&j.status!=Status.CANCELLED&&j.status!=Status.FAILED).findFirst();
        if(existing.isPresent())return existing.get().view();
        if(jobs.size()>=properties.maxJobs() || jobs.values().stream().filter(j->j.owner.equals(owner)).count()>=properties.maxJobsPerSession())throw ApiException.busy();
        String id=UUID.randomUUID().toString();Path directory=root.resolve(id);
        try {Files.createDirectory(directory);}catch(IOException e){throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE,"STORAGE_UNAVAILABLE","暫存空間不足，請稍後再試。");}
        Job job=new Job(id,owner,videoId,formatId,directory,cleanFilename(video.info().title()),Instant.now().plus(properties.downloadTimeout()).plusSeconds(120));
        jobs.put(id,job);
        try {job.future=workers.submit(()->execute(job,video,format));}
        catch(RejectedExecutionException e){jobs.remove(id);deleteTree(directory);throw ApiException.busy();}
        return job.view();
    }
    private void execute(Job job,StoredVideo video,DownloadFormat format) {
        synchronized(job){if(job.cancelled.get())return;job.status=Status.RUNNING;job.message="正在下載與整理影片";}
        try {
            gateway.download(video.info().sourceUrl(),format,job.directory,job.cancelled::get);
            synchronized(job){
                if(job.cancelled.get())throw new CancellationException();
                job.status=Status.READY;job.message="影片已準備好";job.expiresAt=Instant.now().plus(properties.fileTtl());
            }
        }catch(CancellationException e){synchronized(job){job.status=Status.CANCELLED;job.message="已取消影片準備";job.expiresAt=Instant.now().plusSeconds(300);}}
        catch(ApiException e){synchronized(job){if(!job.cancelled.get()){job.status=Status.FAILED;job.message=e.getMessage();job.expiresAt=Instant.now().plusSeconds(300);}}log.info("Video task ended: {}",e.code());}
        catch(Exception e){synchronized(job){if(!job.cancelled.get()){job.status=Status.FAILED;job.message="影片處理失敗，請稍後重試。";job.expiresAt=Instant.now().plusSeconds(300);}}log.warn("Video task failed: {}",e.getClass().getSimpleName());}
        finally{if(job.status!=Status.READY)deleteTree(job.directory);}
    }
    Job ownedJob(String id,String owner) {
        Job job=jobs.get(id);
        if(job==null || !job.owner.equals(owner) || job.expiresAt.isBefore(Instant.now()))throw ApiException.missing();
        return job;
    }
    public JobView getJob(String id,String owner){return ownedJob(id,owner).view();}
    public JobView cancel(String id,String owner){Job job=ownedJob(id,owner);cancelJob(job);return job.view();}
    private void cancelJob(Job job){
        synchronized(job){job.cancelled.set(true);job.status=Status.CANCELLED;job.message="已取消影片準備";job.expiresAt=Instant.now().plusSeconds(60);}
        if(job.future!=null)job.future.cancel(true);
        deleteTree(job.directory);
    }
    public record DownloadFile(Path path,String filename) {}
    public DownloadFile file(String id,String owner){
        Job job=ownedJob(id,owner);
        if(job.status!=Status.READY)throw new ApiException(HttpStatus.CONFLICT,"NOT_READY","影片尚未準備好。");
        Path file=job.directory.resolve("video.mp4");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw ApiException.missing();
        return new DownloadFile(file,job.filename);
    }
    @Scheduled(fixedDelay=60000)
    public synchronized void cleanup(){
        Instant now=Instant.now();videos.entrySet().removeIf(e->e.getValue().info().expiresAt().isBefore(now));
        for(Job job:List.copyOf(jobs.values()))if(job.expiresAt.isBefore(now)){cancelJob(job);jobs.remove(job.id,job);}
        workers.purge();
    }
    static String cleanTitle(String title){
        String clean=title.replaceAll("[\\p{Cntrl}]", " ").strip();
        return clean.isBlank()?"Facebook 影片":clean.substring(0,Math.min(clean.length(),160));
    }
    static String cleanFilename(String title){return cleanTitle(title).replaceAll("[\\\\/:*?\"<>|]","_").replaceAll("^\\.+", "_")+".mp4";}
    static void deleteTree(Path path){
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return;
        try(Stream<Path> paths=Files.walk(path)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}
        catch(IOException ignored){}
    }
    @PreDestroy
    public void close(){
        jobs.values().forEach(this::cancelJob);workers.shutdownNow();
        try{workers.awaitTermination(8,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        deleteTree(root);
    }
}
