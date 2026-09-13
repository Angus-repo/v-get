package tw.angus.clippocket;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class FacebookUrlPolicyTest {
    private final FacebookUrlPolicy policy=new FacebookUrlPolicy();
    @ParameterizedTest
    @ValueSource(strings={"https://www.facebook.com/watch/?v=123456","https://m.facebook.com/reel/123456/",
        "https://www.facebook.com/user/videos/123456","https://www.facebook.com/share/r/AbC123/",
        "https://www.facebook.com/share/v/AbC123/","https://fb.watch/AbC123/","https://www.facebook.com/video.php?v=123456&foo=bar"})
    void acceptsVideoLinks(String url){assertEquals(url,policy.validate(url));}
    @ParameterizedTest
    @ValueSource(strings={"http://www.facebook.com/watch/?v=123","https://facebook.com.evil.example/reel/123",
        "https://www.facebook.com@127.0.0.1/reel/123","https://127.0.0.1/reel/123","file:///etc/passwd",
        "https://www.facebook.com:8443/reel/123","https://www.facebook.com/l.php?u=http://127.0.0.1",
        "https://www.facebook.com/reel/%2e%2e/123","https://www.facebook.com/login","https://www.facebook.com/",
        "https://fb.watch/../../admin","https://www.facebook.com/reel/123\n--exec=id"})
    void rejectsOffDomainCredentialsEndpointsAndInjection(String url){assertThrows(ApiException.class,()->policy.validate(url));}
}
