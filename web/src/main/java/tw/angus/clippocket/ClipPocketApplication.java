package tw.angus.clippocket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(VideoProperties.class)
public class ClipPocketApplication {
    public static void main(String[] args) { SpringApplication.run(ClipPocketApplication.class, args); }
}
