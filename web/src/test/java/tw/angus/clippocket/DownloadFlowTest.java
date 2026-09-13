package tw.angus.clippocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DownloadFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean YtDlpGateway gateway;
    @BeforeEach void fakeExtractor() throws Exception {
        when(gateway.inspect(anyString(),any())).thenReturn(mapper.readTree("{\"title\":\"測試影片\",\"uploader\":\"fixture\",\"duration\":5}"));
        when(gateway.formats(any())).thenReturn(List.of(new VideoModels.DownloadFormat("q720","hd",720,"MP4 · 含聲音")));
        doAnswer(invocation->{Files.write(((Path)invocation.getArgument(2)).resolve("video.mp4"),new byte[]{0,1,2,3,4,5,6,7});return null;}).when(gateway).download(anyString(),any(),any(),any());
    }
    @Test void inspectPrepareRangeDownloadAndSessionIsolation() throws Exception {
        MockHttpSession owner=new MockHttpSession();MockHttpSession stranger=new MockHttpSession();
        String metadata=mvc.perform(post("/api/videos/inspect").session(owner).contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://www.facebook.com/reel/123456/\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String videoId=mapper.readTree(metadata).path("id").asText();
        String body=mapper.writeValueAsString(java.util.Map.of("videoId",videoId,"formatId","q720"));
        mvc.perform(post("/api/jobs").session(stranger).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        String jobJson=mvc.perform(post("/api/jobs").session(owner).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String jobId=mapper.readTree(jobJson).path("id").asText();
        long deadline=System.nanoTime()+Duration.ofSeconds(4).toNanos();boolean ready=false;
        do {
            String result=mvc.perform(get("/api/jobs/"+jobId).session(owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            ready=mapper.readTree(result).path("status").asText().equals("READY");if(!ready)Thread.sleep(20);
        }while(!ready&&System.nanoTime()<deadline);assertTrue(ready);
        mvc.perform(get("/api/jobs/"+jobId+"/file").session(stranger)).andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs/"+jobId+"/file").session(owner).header("Range","bytes=0-3"))
            .andExpect(status().isPartialContent()).andExpect(header().string("Content-Range","bytes 0-3/8"))
            .andExpect(content().bytes(new byte[]{0,1,2,3}));
        mvc.perform(delete("/api/jobs/"+jobId).session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/api/jobs/"+jobId+"/file").session(owner)).andExpect(status().isConflict());
    }
    @Test void rejectsCrossSiteRequests() throws Exception {
        mvc.perform(post("/api/videos/inspect").header("Origin","https://other.example").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://www.facebook.com/reel/123/\"}"))
            .andExpect(status().isForbidden());
        verify(gateway,never()).inspect(anyString(),any());
    }
    @Test void rejectsOversizedJsonBeforeExtraction() throws Exception {
        mvc.perform(post("/api/videos/inspect").contentType(MediaType.APPLICATION_JSON).content("x".repeat(9000)))
            .andExpect(status().isPayloadTooLarge());verify(gateway,never()).inspect(anyString(),any());
    }
}
