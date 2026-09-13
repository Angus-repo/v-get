package tw.angus.clippocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestGuard extends OncePerRequestFilter {
    private static final long MAX_BODY=8192;
    private final ObjectMapper mapper;
    private final String publicOrigin;
    public RequestGuard(ObjectMapper mapper,@Value("${PUBLIC_ORIGIN:}") String publicOrigin){this.mapper=mapper;this.publicOrigin=publicOrigin.replaceAll("/$","");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Referrer-Policy","no-referrer");
        response.setHeader("X-Frame-Options","DENY");
        response.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; media-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
        if(!request.getRequestURI().startsWith("/api/")){chain.doFilter(request,response);return;}
        response.setHeader("Cache-Control","no-store");
        String fetchSite=request.getHeader("Sec-Fetch-Site");
        if("cross-site".equals(fetchSite)){fail(response,403,"FORBIDDEN","請從本工具的網頁操作。");return;}
        boolean mutation=!request.getMethod().equals("GET")&&!request.getMethod().equals("HEAD");
        if(mutation){
            String origin=request.getHeader("Origin");
            String expected=publicOrigin.isBlank()?request.getScheme()+"://"+request.getServerName()
                +((request.getServerPort()==80&&request.getScheme().equals("http")||request.getServerPort()==443&&request.getScheme().equals("https"))?"":":"+request.getServerPort()):publicOrigin;
            if(origin!=null&&!origin.equals(expected)){fail(response,403,"FORBIDDEN","來源網址不符，請從本工具的網頁操作。");return;}
            if(request.getMethod().equals("POST")&&(request.getContentType()==null||!request.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("application/json"))){fail(response,415,"INVALID_CONTENT_TYPE","請使用 JSON 格式。");return;}
            if(request.getContentLengthLong()>MAX_BODY){fail(response,413,"BODY_TOO_LARGE","請求內容過大。");return;}
            chain.doFilter(new HttpServletRequestWrapper(request){
                @Override public ServletInputStream getInputStream() throws IOException {
                    ServletInputStream source=super.getInputStream();
                    return new ServletInputStream(){
                        private long count;
                        @Override public int read() throws IOException {int value=source.read();if(value>=0&&++count>MAX_BODY)throw new IOException("Request body limit exceeded");return value;}
                        @Override public int read(byte[] b,int off,int len)throws IOException {int n=source.read(b,off,(int)Math.min(len,MAX_BODY-count+1));if(n>0){count+=n;if(count>MAX_BODY)throw new IOException("Request body limit exceeded");}return n;}
                        @Override public boolean isFinished(){return source.isFinished();}
                        @Override public boolean isReady(){return source.isReady();}
                        @Override public void setReadListener(ReadListener listener){source.setReadListener(listener);}
                        @Override public void close() throws IOException{source.close();}
                    };
                }
            },response);return;
        }
        chain.doFilter(request,response);
    }
    private void fail(HttpServletResponse response,int status,String code,String message)throws IOException{
        response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(),Map.of("code",code,"message",message));
    }
}
