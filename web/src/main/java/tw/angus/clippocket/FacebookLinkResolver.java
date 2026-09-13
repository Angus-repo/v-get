package tw.angus.clippocket;

import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;

@Component
public class FacebookLinkResolver {
    private final FacebookUrlPolicy policy;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Pattern TAGS=Pattern.compile("<(?:meta|link)\\b[^>]*>",Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRS=Pattern.compile("([\\w:-]+)\\s*=\\s*([\"'])(.*?)\\2",Pattern.DOTALL);
    public FacebookLinkResolver(FacebookUrlPolicy policy){this.policy=policy;}
    record Page(int status,String location,String html){}
    public String resolve(String input){
        String current=policy.validate(input);
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        for(int hop=0;hop<4;hop++){
            URI uri=URI.create(current);
            if(!uri.getHost().endsWith("fb.watch")&&!uri.getPath().startsWith("/share/"))return current;
            if(System.nanoTime()>deadline)throw ApiException.unavailable("分享連結回應逾時，請改貼影片的完整網址。");
            Page page=fetch(uri);
            if(page.status()>=300&&page.status()<400&&page.location()!=null){
                // Every redirect is revalidated before a request; no Facebook link-shim or external host.
                current=policy.validate(uri.resolve(page.location()).toString());continue;
            }
            if(page.status()==200){
                Matcher tags=TAGS.matcher(page.html());
                while(tags.find()){
                    Map<String,String> attrs=new HashMap<>();Matcher pairs=ATTRS.matcher(tags.group());
                    while(pairs.find())attrs.put(pairs.group(1).toLowerCase(Locale.ROOT),pairs.group(3));
                    String candidate="og:url".equals(attrs.get("property"))?attrs.get("content"):
                        "canonical".equals(attrs.get("rel"))?attrs.get("href"):null;
                    if(candidate!=null){
                        candidate=candidate.replace("&amp;","&").replace("&#38;","&");
                        String validated=policy.validate(uri.resolve(candidate).toString());
                        if(!validated.equals(current)){current=validated;break;}
                    }
                }
                if(!current.equals(uri.toString()))continue;
            }
            throw ApiException.unavailable("分享連結無法展開，可能需要登入。請開啟影片後，改貼含 videos、reel 或 watch 的完整網址。");
        }
        throw ApiException.unavailable("分享連結轉址次數過多，請改貼影片的完整網址。");
    }
    protected Page fetch(URI uri){
        try{
            for(InetAddress address:InetAddress.getAllByName(uri.getHost())){
                byte[] raw=address.getAddress();
                if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()
                    ||address.isSiteLocalAddress()||address.isMulticastAddress()
                    ||raw.length==16&&(raw[0]&0xfe)==0xfc
                    ||raw.length==4&&((raw[0]&0xff)==0||(raw[0]&0xff)>=224
                      ||(raw[0]&0xff)==100&&((raw[1]&0xff)>=64&&(raw[1]&0xff)<=127)))
                    throw ApiException.invalid("來源位址不允許存取。");
            }
            HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                .header("User-Agent","Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36")
                .header("Accept","text/html").GET().build();
            HttpResponse<byte[]> response=client.send(request,ignored->new BoundedSubscriber(256*1024));
            return new Page(response.statusCode(),response.headers().firstValue("location").orElse(null),new String(response.body(),StandardCharsets.UTF_8));
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw ApiException.unavailable("已停止分享連結處理。");}
        catch(java.io.IOException e){throw ApiException.unavailable("目前無法展開分享連結，請改貼影片的完整網址。");}
    }
    static class BoundedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream out=new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();
        private Flow.Subscription subscription;
        BoundedSubscriber(int limit){this.limit=limit;}
        @Override public CompletionStage<byte[]> getBody(){return body;}
        @Override public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers){
            if(body.isDone())return;
            for(ByteBuffer buffer:buffers){int length=Math.min(buffer.remaining(),limit-out.size());byte[] chunk=new byte[length];buffer.get(chunk);out.writeBytes(chunk);
                if(out.size()>=limit){subscription.cancel();body.complete(out.toByteArray());return;}}
            subscription.request(1);
        }
        @Override public void onError(Throwable error){body.completeExceptionally(error);}
        @Override public void onComplete(){body.complete(out.toByteArray());}
    }
}
