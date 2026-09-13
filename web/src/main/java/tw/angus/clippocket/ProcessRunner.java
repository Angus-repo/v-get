package tw.angus.clippocket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ProcessRunner {
    private final Semaphore permits = new Semaphore(3);
    public String run(List<String> args, Path directory, Duration timeout, BooleanSupplier cancelled, long maxWorkingBytes) {
        if (!permits.tryAcquire()) throw ApiException.busy();
        Process process = null;
        ExecutorService readers = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "extractor-output"); t.setDaemon(true); return t; });
        try {
            process = new ProcessBuilder(args).directory(directory.toFile()).redirectErrorStream(true).start();
            Process child = process;
            AtomicBoolean overflow = new AtomicBoolean();
            Future<String> output = readers.submit(() -> readBounded(child.getInputStream(), overflow));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new CancellationException();
                if (overflow.get()) throw ApiException.unavailable("來源回傳內容過大，無法處理。");
                if (System.nanoTime() > deadline) throw new ApiException(HttpStatus.GATEWAY_TIMEOUT,"TIMEOUT","影片處理逾時，請稍後重試。");
                if (maxWorkingBytes > 0 && directoryBytes(directory) > maxWorkingBytes)
                    throw ApiException.unavailable("影片暫存超過大小限制，請改用較低畫質。");
            }
            if (cancelled.getAsBoolean()) throw new CancellationException();
            String result = output.get(3, TimeUnit.SECONDS);
            if (overflow.get()) throw ApiException.unavailable("來源回傳內容過大，無法處理。");
            if (process.exitValue() != 0) throw ApiException.unavailable("無法取得影片。可能需要登入、來源限制存取、網址已失效，或影片格式已變更。");
            return result;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"EXTRACTOR_UNAVAILABLE","影片處理程式無法啟動，請聯絡管理員。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new CancellationException();
        } catch (ExecutionException | TimeoutException e) {
            throw ApiException.unavailable("無法讀取影片處理結果，請重試。");
        } finally {
            if (process != null) terminate(process);
            readers.shutdownNow(); permits.release();
        }
    }
    private static String readBounded(InputStream stream, AtomicBoolean overflow) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        try (stream) {
            while ((n = stream.read(buffer)) >= 0) {
                if (out.size() + n > 4 * 1024 * 1024) { overflow.set(true); return ""; }
                out.write(buffer, 0, n);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }
    static long directoryBytes(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                .mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return Long.MAX_VALUE / 1024; } }).sum();
        }
    }
    private static void terminate(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
        try { process.waitFor(2,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
