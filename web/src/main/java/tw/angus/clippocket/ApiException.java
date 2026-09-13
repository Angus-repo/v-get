package tw.angus.clippocket;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public ApiException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message); }
    public static ApiException unavailable(String message) { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VIDEO_UNAVAILABLE", message); }
    public static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "影片或工作已過期，請重新解析。"); }
    public static ApiException busy() { return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "BUSY", "目前工作較多，請稍後重試。"); }
}
