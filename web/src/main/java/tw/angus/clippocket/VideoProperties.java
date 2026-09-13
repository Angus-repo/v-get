package tw.angus.clippocket;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("clippocket")
public record VideoProperties(String executable, Path workDir, Duration inspectTimeout,
        Duration downloadTimeout, Duration metadataTtl, Duration fileTtl,
        long maxFileBytes, long maxWorkingBytes, int maxDurationSeconds,
        int maxJobs, int maxJobsPerSession, int maxMetadata) {
    public VideoProperties {
        if (executable == null || executable.isBlank() || workDir == null
            || inspectTimeout == null || inspectTimeout.isNegative() || inspectTimeout.isZero()
            || downloadTimeout == null || downloadTimeout.isNegative() || downloadTimeout.isZero()
            || metadataTtl == null || metadataTtl.isNegative() || metadataTtl.isZero()
            || fileTtl == null || fileTtl.isNegative() || fileTtl.isZero()
            || maxFileBytes <= 0 || maxWorkingBytes < maxFileBytes || maxDurationSeconds <= 0
            || maxJobs < 1 || maxJobsPerSession < 1 || maxMetadata < 1) {
            throw new IllegalArgumentException("Invalid ClipPocket configuration");
        }
    }
}
