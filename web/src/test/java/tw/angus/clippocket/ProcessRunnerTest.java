package tw.angus.clippocket;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProcessRunnerTest {
    @TempDir Path directory;
    @Test void timesOutAndStopsChildProcess(){
        ProcessRunner runner=new ProcessRunner();long start=System.nanoTime();
        ApiException error=assertThrows(ApiException.class,()->runner.run(List.of("/bin/sleep","20"),directory,Duration.ofMillis(300),()->false,0));
        assertEquals("TIMEOUT",error.code());assertTrue(Duration.ofNanos(System.nanoTime()-start).toSeconds()<5);
    }
    @Test void honorsCancellation(){assertThrows(CancellationException.class,()->new ProcessRunner().run(List.of("/bin/sleep","20"),directory,Duration.ofSeconds(20),()->true,0));}
    @Test void passesArgumentsWithoutShellEvaluation(){
        String result=new ProcessRunner().run(List.of("/usr/bin/printf","%s","$(echo unexpected); test"),directory,Duration.ofSeconds(2),()->false,0);
        assertEquals("$(echo unexpected); test",result);
    }
}
