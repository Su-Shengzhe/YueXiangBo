package cn.community.foundation;
import jakarta.servlet.http.*;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
@Configuration @Profile("business") @EnableScheduling
public class BusinessWeb implements WebMvcConfigurer {
 private final Tokens tokens;private final Optional<IdentityLookup> local;private final RestClient identity;private final String serviceToken;
 public BusinessWeb(Tokens tokens,Optional<IdentityLookup> local,@Value("${business.identity-url:http://127.0.0.1:9081}")String url,@Value("${business.service-token}")String serviceToken){
  this.tokens=tokens;this.local=local;this.serviceToken=serviceToken;
  var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(2000);factory.setReadTimeout(2000);identity=RestClient.builder().baseUrl(url).requestFactory(factory).build();
 }
 public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new HandlerInterceptor(){
  @SuppressWarnings("unchecked") public boolean preHandle(HttpServletRequest req,HttpServletResponse response,Object handler)throws Exception{
   req.setAttribute("traceId",UUID.randomUUID().toString());String path=req.getRequestURI();
   if(path.startsWith("/internal/v1/")){
    String value=req.getHeader("X-Service-Token");Problem.require(value!=null&&MessageDigest.isEqual(serviceToken.getBytes(StandardCharsets.UTF_8),value.getBytes(StandardCharsets.UTF_8)),403,"INTERNAL_FORBIDDEN","内部接口拒绝访问");return true;
   }
   if(!path.startsWith("/api/v1/")||path.equals("/api/v1/auth/login")||path.equals("/api/v1/communities")&&req.getMethod().equals("GET")||path.equals("/api/v1/spaces/available")&&req.getMethod().equals("GET"))return true;
   String bearer=req.getHeader("Authorization");tokens.verify(bearer);Map<String,Object> state;
   if(local.isPresent())state=local.get().state(bearer);
   else try{state=identity.get().uri("/internal/v1/auth/state").header("Authorization",bearer).header("X-Service-Token",serviceToken).retrieve().body(Map.class);}
    catch(org.springframework.web.client.RestClientResponseException e){int code=e.getStatusCode().value();if(code==401||code==403)throw new Problem(code,"AUTH_REJECTED","登录已失效或账号无权限");throw new Problem(503,"IDENTITY_UNAVAILABLE","身份服务暂不可用");}
    catch(Exception e){throw new Problem(503,"IDENTITY_UNAVAILABLE","身份服务暂不可用");}
   req.setAttribute("actor",Actor.from(state));return true;
  }
 }).addPathPatterns("/**");}
}
