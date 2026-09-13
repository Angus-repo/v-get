package tw.angus.clippocket;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static tw.angus.clippocket.VideoModels.*;

@RestController
@RequestMapping("/api")
public class VideoController {
    private final VideoService service;
    public VideoController(VideoService service){this.service=service;}
    public record InspectRequest(@NotBlank @Size(max=2048) String url){}
    public record DownloadRequest(@NotBlank @Size(max=60) String videoId,@NotBlank @Size(max=100) String formatId){}

    @GetMapping("/health")
    public Map<String,String> health(){return Map.of("status","UP","service","clippocket");}

    @PostMapping("/videos/inspect")
    public VideoInfo inspect(@RequestBody @Valid InspectRequest request,HttpSession session){
        limitInspectRate(session);return service.inspect(request.url(),session.getId());
    }
    @PostMapping("/jobs")
    public ResponseEntity<JobView> create(@RequestBody @Valid DownloadRequest body,HttpServletRequest request){
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.createJob(body.videoId(),body.formatId(),owner(request)));
    }
    @GetMapping("/jobs/{id}")
    public JobView get(@PathVariable String id,HttpServletRequest request){return service.getJob(id,owner(request));}
    @DeleteMapping("/jobs/{id}")
    public JobView delete(@PathVariable String id,HttpServletRequest request){return service.cancel(id,owner(request));}
    @GetMapping("/jobs/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable String id,@RequestParam(defaultValue="false") boolean inline,HttpServletRequest request) throws IOException {
        VideoService.DownloadFile file=service.file(id,owner(request));
        ContentDisposition disposition=(inline?ContentDisposition.inline():ContentDisposition.attachment())
            .filename(file.filename(),StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("video/mp4"))
            .contentLength(Files.size(file.path())).header(HttpHeaders.CONTENT_DISPOSITION,disposition.toString())
            .header(HttpHeaders.ACCEPT_RANGES,"bytes").cacheControl(CacheControl.noStore())
            .body(new FileSystemResource(file.path()));
    }
    private String owner(HttpServletRequest request){HttpSession session=request.getSession(false);if(session==null)throw ApiException.missing();return session.getId();}
    private void limitInspectRate(HttpSession session){
        synchronized(session){
            long now=System.currentTimeMillis();Long start=(Long)session.getAttribute("rateStart");
            int count=session.getAttribute("rateCount") instanceof Integer i?i:0;
            if(start==null || now-start>60000){session.setAttribute("rateStart",now);count=0;}
            if(count>=10)throw ApiException.busy();session.setAttribute("rateCount",count+1);
        }
    }
}
