package tw.angus.clippocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class YtDlpGatewayTest {
    static VideoProperties properties(Path root){return new VideoProperties("yt-dlp",root,Duration.ofSeconds(1),Duration.ofSeconds(3),Duration.ofMinutes(10),Duration.ofMinutes(30),262144000,786432000,1800,8,3,40);}
    private final ObjectMapper mapper=new ObjectMapper();
    private final YtDlpGateway gateway=new YtDlpGateway(mock(ProcessRunner.class),properties(Path.of("/tmp")),mapper);
    @Test void buildsOnlySupportedPlayableFormats() throws Exception {
        var info=mapper.readTree("""
          {"formats":[
            {"format_id":"a1","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":128},
            {"format_id":"v1","ext":"mp4","vcodec":"avc1.4d401f","acodec":"none","height":1080},
            {"format_id":"sd","ext":"mp4","vcodec":"avc1.4d401f","acodec":"mp4a.40.2","height":480},
            {"format_id":"webm","ext":"webm","vcodec":"vp9","acodec":"opus","height":2160},
            {"format_id":"bad;exec","ext":"mp4","vcodec":"h264","acodec":"aac","height":240}
          ]}
          """);
        var formats=gateway.formats(info);assertEquals(2,formats.size());
        assertEquals("v1+a1",formats.get(0).selector());assertEquals("sd",formats.get(1).selector());
    }
    @Test void refusesSilentVideoWhenNoAudioCanBeMerged() throws Exception {
        var info=mapper.readTree("""
          {"formats":[{"format_id":"v","ext":"mp4","vcodec":"h264","acodec":"none","height":1080}]}
          """);
        assertThrows(ApiException.class,()->gateway.formats(info));
    }
    @Test void keepsFacebookProgressiveSourcesWithUnknownCodecs() throws Exception {
        var info=mapper.readTree("""
          {"formats":[{"format_id":"hd","ext":"mp4","url":"https://video.xx.fbcdn.net/sample.mp4"}]}
          """);
        var formats=gateway.formats(info);assertEquals("qhd",formats.get(0).id());assertEquals(0,formats.get(0).height());
    }
}
