package tw.angus.clippocket;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class FacebookUrlPolicy {
    private static final Set<String> HOSTS = Set.of("facebook.com", "www.facebook.com", "m.facebook.com",
        "web.facebook.com", "mbasic.facebook.com", "fb.watch", "www.fb.watch");
    public String validate(String input) {
        if (input == null || input.isBlank() || input.length() > 2048 || input.chars().anyMatch(Character::isISOControl))
            throw ApiException.invalid("請提供完整的 Facebook 影片連結。");
        try {
            URI uri = new URI(input.strip());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || !HOSTS.contains(host.toLowerCase(Locale.ROOT))
                || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getRawPath().contains("%")
                || uri.getRawPath().contains("\\") || uri.getRawPath().contains(".."))
                throw ApiException.invalid("只接受 HTTPS 的 Facebook 影片或 fb.watch 連結。");
            String path = uri.getPath();
            if (path == null || path.isBlank() || path.equals("/"))
                throw ApiException.invalid("請提供特定影片的連結，而非 Facebook 主頁。");
            // Reject link-shim / arbitrary Facebook endpoints; only known video routes reach yt-dlp.
            boolean allowed = host.toLowerCase(Locale.ROOT).endsWith("fb.watch")
                ? path.matches("/[A-Za-z0-9_-]+/?")
                : path.matches("/(?:reel|reels)/[0-9]+/?")
                    || path.matches("/(?:[^/]+/)?videos/(?:[^/]+/)?[0-9]+/?")
                    || path.matches("/share/(?:r|v)/[A-Za-z0-9_-]+/?")
                    || path.matches("/(?:watch/?|video\\.php)") && uri.getRawQuery() != null
                        && uri.getRawQuery().matches("(?:.*&)?v=[0-9]+(?:&.*)?");
            if (!allowed) throw ApiException.invalid("此連結不是支援的 Facebook 影片網址，請重新複製影片分享連結。");
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT)
                + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (java.net.URISyntaxException e) { throw ApiException.invalid("影片網址格式不正確。"); }
    }
}
