package tw.angus.clippocket;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger log=LoggerFactory.getLogger(ApiErrorHandler.class);
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String,String>> api(ApiException e){return ResponseEntity.status(e.status()).body(Map.of("code",e.code(),"message",e.getMessage()));}
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String,String>> invalid(Exception e){return ResponseEntity.badRequest().body(Map.of("code","INVALID_INPUT","message","輸入資料格式不正確，請檢查後再試。"));}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,String>> unexpected(Exception e){
        log.warn("Request failed: {}",e.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Map.of("code","INTERNAL_ERROR","message","服務暫時無法完成工作，請稍後重試。"));
    }
}
