package cn.community.foundation;
import java.util.*;
import java.time.Instant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
@RestControllerAdvice @Profile("business")
public class BusinessErrors implements ResponseBodyAdvice<Object> {
 public boolean supports(MethodParameter p,Class<? extends HttpMessageConverter<?>> c){return p.getContainingClass().getPackageName().startsWith("cn.community");}
 public Object beforeBodyWrite(Object body,MethodParameter p,MediaType m,Class<? extends HttpMessageConverter<?>> c,ServerHttpRequest req,ServerHttpResponse res){
  if(!req.getURI().getPath().startsWith("/api/v1/"))return body;
  if(body instanceof Map<?,?> map&&map.containsKey("traceId")&&map.containsKey("code"))return body;
  return envelope("OK","成功",body,((ServletServerHttpRequest)req).getServletRequest());
 }
 private Map<String,Object> envelope(String code,String msg,Object body,HttpServletRequest req){Map<String,Object> m=new LinkedHashMap<>();m.put("code",code);m.put("message",msg);m.put("data",body);m.put("traceId",Objects.toString(req.getAttribute("traceId"),UUID.randomUUID().toString()));m.put("serverTime",Instant.now().toString());return m;}
 @ExceptionHandler(Problem.class) public ResponseEntity<?> business(Problem e,HttpServletRequest req){return ResponseEntity.status(e.status).body(envelope(e.code,e.getMessage(),null,req));}
 @ExceptionHandler({IllegalArgumentException.class,org.springframework.web.bind.MissingRequestHeaderException.class,java.time.format.DateTimeParseException.class,org.springframework.web.bind.MissingServletRequestParameterException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
 public ResponseEntity<?> invalid(Exception e,HttpServletRequest req){return ResponseEntity.unprocessableEntity().body(envelope("INVALID_ARGUMENT","参数格式无效",null,req));}
 @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class) public ResponseEntity<?> duplicate(Exception e,HttpServletRequest req){return ResponseEntity.status(409).body(envelope("DUPLICATE_RESOURCE","记录已存在",null,req));}
 @ExceptionHandler(Exception.class) public ResponseEntity<?> other(Exception e,HttpServletRequest req){org.slf4j.LoggerFactory.getLogger(getClass()).error("Business request failed trace={}",req.getAttribute("traceId"),e);return ResponseEntity.status(500).body(envelope("INTERNAL_ERROR","操作失败，请使用原请求重试或查询",null,req));}
}
