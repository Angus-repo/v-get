package tw.angus.clippocket;

import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FacebookLinkResolverTest {
    @Test void unfoldsShortLinkIntoSupportedVideoRoute(){
        var resolver=spy(new FacebookLinkResolver(new FacebookUrlPolicy()));
        doReturn(new FacebookLinkResolver.Page(302,"https://www.facebook.com/reel/123456/",""))
            .when(resolver).fetch(URI.create("https://fb.watch/AbC123/"));
        assertEquals("https://www.facebook.com/reel/123456/",resolver.resolve("https://fb.watch/AbC123/"));
    }
    @Test void refusesOffDomainRedirectBeforeFetchingIt(){
        var resolver=spy(new FacebookLinkResolver(new FacebookUrlPolicy()));
        doReturn(new FacebookLinkResolver.Page(302,"http://127.0.0.1/admin",""))
            .when(resolver).fetch(URI.create("https://fb.watch/AbC123/"));
        assertThrows(ApiException.class,()->resolver.resolve("https://fb.watch/AbC123/"));
        verify(resolver,times(1)).fetch(any());
    }
    @Test void extractsOnlyExplicitCanonicalMetadata(){
        var resolver=spy(new FacebookLinkResolver(new FacebookUrlPolicy()));
        doReturn(new FacebookLinkResolver.Page(200,null,"<meta content='https://www.facebook.com/watch/?v=123&amp;source=share' property='og:url'>"))
            .when(resolver).fetch(URI.create("https://www.facebook.com/share/v/AbC123/"));
        assertEquals("https://www.facebook.com/watch/?v=123&source=share",resolver.resolve("https://www.facebook.com/share/v/AbC123/"));
    }
}
