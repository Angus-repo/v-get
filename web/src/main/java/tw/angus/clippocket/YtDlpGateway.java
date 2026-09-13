package tw.angus.clippocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import static tw.angus.clippocket.VideoModels.*;

@Component
public class YtDlpGateway {
    private final ProcessRunner runner;
    private final VideoProperties properties;
    private final ObjectMapper mapper;
    public YtDlpGateway(ProcessRunner runner, VideoProperties properties, ObjectMapper mapper) {
        this.runner=runner; this.properties=properties; this.mapper=mapper;
    }
    private List<String> base() {
        return new ArrayList<>(List.of(properties.executable(),"--ignore-config","--no-playlist","--no-warnings",
            "--no-progress","--no-cache-dir","--socket-timeout","15","--retries","1",
            "--extractor-retries","1","--fragment-retries","1","--concurrent-fragments","1",
            "--use-extractors","facebook.*"));
    }
    public JsonNode inspect(String url, Path directory) {
        List<String> args=base(); args.addAll(List.of("--dump-single-json","--skip-download","--",url));
        String output=runner.run(args,directory,properties.inspectTimeout(),()->false,0);
        try {
            JsonNode result=mapper.readTree(output);
            if(result==null || result.has("entries") || result.path("is_live").asBoolean()
                || "is_live".equals(result.path("live_status").asText())) throw ApiException.unavailable("目前只支援單一影片，不支援播放清單或直播中內容。");
            if(result.path("duration").asDouble(0)>properties.maxDurationSeconds()) throw ApiException.unavailable("影片長度超過 30 分鐘，請改用較短的影片。");
            return result;
        } catch(IOException e){throw ApiException.unavailable("影片資訊格式已變更，請更新解析程式後再試。");}
    }
    public List<DownloadFormat> formats(JsonNode info) {
        List<JsonNode> formats=new ArrayList<>();info.path("formats").forEach(formats::add);
        Optional<JsonNode> audio=formats.stream().filter(f->safeId(f) && "none".equals(f.path("vcodec").asText())
            && codecPresent(f,"acodec") && "m4a".equals(f.path("ext").asText()))
            .max(Comparator.comparingDouble(f->f.path("abr").asDouble(0)));
        Map<Integer,DownloadFormat> options=new TreeMap<>(Comparator.reverseOrder());
        formats.stream().sorted(Comparator.comparingDouble(f->f.path("tbr").asDouble(0))).forEach(f->{
            if(!safeId(f) || !"mp4".equals(f.path("ext").asText()))return;
            boolean progressiveUnknown=!f.hasNonNull("vcodec")&&!f.hasNonNull("acodec")
                &&f.path("url").asText("").matches("https?://.+")&&!f.hasNonNull("manifest_url");
            String videoCodec=f.path("vcodec").asText();
            if(!progressiveUnknown&&!(videoCodec.startsWith("avc") || videoCodec.startsWith("h264")))return;
            long size=f.path("filesize").asLong(0);
            if(size>properties.maxFileBytes())return;
            String selector=f.path("format_id").asText();
            if(!progressiveUnknown&&!codecPresent(f,"acodec")) {if(audio.isEmpty())return;selector+="+"+audio.get().path("format_id").asText();}
            int height=Math.max(0,f.path("height").asInt(0));
            String detail=progressiveUnknown?"MP4 · 完成後檢查聲音":"MP4 · 含聲音";
            boolean hd=f.path("format_id").asText("").contains("hd");
            int rank=height>0?height:hd?1:0;
            options.put(rank,new DownloadFormat(height>0?"q"+height:hd?"qhd":"qsd",selector,height,detail));
        });
        if(options.isEmpty())throw ApiException.unavailable("未找到可用的 MP4 影像與音訊格式。此影片目前無法下載。");
        return List.copyOf(options.values()).stream().limit(6).toList();
    }
    static boolean codecPresent(JsonNode f,String key) {String s=f.path(key).asText("");return !s.isBlank() && !s.equals("none");}
    private static boolean safeId(JsonNode f) {return f.path("format_id").asText("").matches("[A-Za-z0-9_.-]{1,100}");}
    public void download(String url, DownloadFormat format, Path directory, BooleanSupplier cancelled) {
        List<String> args=base();
        args.addAll(List.of("--format",format.selector(),"--merge-output-format","mp4","--max-filesize",
            Long.toString(properties.maxFileBytes()),"--no-part","--no-continue","--no-mtime",
            "--match-filters","!is_live & duration <=? "+properties.maxDurationSeconds(),
            "--output","video.%(ext)s","--",url));
        runner.run(args,directory,properties.downloadTimeout(),cancelled,properties.maxWorkingBytes());
        Path file=directory.resolve("video.mp4");
        try {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)==0)
                throw ApiException.unavailable("沒有產生完整影片，影片可能超過限制或來源已變更。");
            if(Files.size(file)>properties.maxFileBytes())throw ApiException.unavailable("影片超過 250 MB，請選擇較低畫質。");
            String probe=runner.run(List.of("ffprobe","-v","error","-show_streams","-show_format","-of","json","video.mp4"),
                directory,java.time.Duration.ofSeconds(15),cancelled,properties.maxWorkingBytes());
            JsonNode media=mapper.readTree(probe);
            boolean video=false,audioPresent=false;
            for(JsonNode stream:media.path("streams")){
                if("video".equals(stream.path("codec_type").asText())&&"h264".equals(stream.path("codec_name").asText()))video=true;
                if("audio".equals(stream.path("codec_type").asText()))audioPresent=true;
            }
            if(!video||!audioPresent)throw ApiException.unavailable("此格式缺少可用的 H.264 影像或音訊，請改用其他畫質。");
            if(media.path("format").path("duration").asDouble(0)>properties.maxDurationSeconds())throw ApiException.unavailable("影片長度超過 30 分鐘。");
        }catch(IOException e){throw ApiException.unavailable("無法讀取完成的影片，請重試。");}
    }
}
