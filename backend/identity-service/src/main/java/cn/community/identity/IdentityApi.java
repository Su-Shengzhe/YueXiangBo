package cn.community.identity;
import cn.community.foundation.*;
import java.util.*;
import java.time.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
@RestController @Profile("business")
public class IdentityApi implements IdentityLookup {
 private final JdbcTemplate db;private final Tokens tokens;private final StringRedisTemplate redis;private final TransactionTemplate tx;
 private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(12);
 private final String dummy=encoder.encode(UUID.randomUUID().toString());
 public IdentityApi(JdbcTemplate db,Tokens tokens,StringRedisTemplate redis,PlatformTransactionManager manager){this.db=db;this.tokens=tokens;this.redis=redis;this.tx=new TransactionTemplate(manager);}
 @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="business.demo",havingValue="true") ApplicationRunner demoAccounts(){return args->{if(db.queryForObject("SELECT COUNT(*) FROM app_user",Integer.class)==0)tx.executeWithoutResult(s->{String[] names={"driver","driver2","owner","property","admin"};String[] roles={"DRIVER","DRIVER","OWNER","PROPERTY","PLATFORM"};for(int i=0;i<names.length;i++){String password=System.getenv("DEMO_"+names[i].toUpperCase()+"_PASSWORD");if(password==null||password.length()<16)throw new IllegalStateException("Private demo credentials required");db.update("INSERT INTO app_user(id,username,password_hash,role,community_id) VALUES(?,?,?,?,?)",i+1,names[i],encoder.encode(password),roles[i],i==3?10:null);db.update("INSERT INTO user_role VALUES(?,?)",i+1,roles[i]);if(i==3)db.update("INSERT INTO community_admin VALUES(?,10)",i+1);}});};}
 @PostMapping("/api/v1/auth/login") public Map<String,Object> login(@RequestBody Map<String,Object> input,HttpServletRequest req){
  String username=Data.str(input,"username").toLowerCase(Locale.ROOT),password=Data.str(input,"password");
  Problem.require(username.matches("[a-zA-Z0-9_]{1,40}")&&password.length()<=100,422,"INVALID_ARGUMENT","账号或密码格式无效");
  String bucket="login:"+Data.hash(req.getRemoteAddr()+":"+username);
  Long count=redis.execute(LoginLimit.SCRIPT,List.of(bucket),"60000");
  Problem.require(count!=null&&count<=10,429,"LOGIN_RATE_LIMIT","登录过于频繁，请一分钟后再试");
  var rows=db.queryForList("SELECT * FROM app_user WHERE username=?",username);String hash=rows.isEmpty()?dummy:rows.getFirst().get("password_hash").toString();
  boolean match=encoder.matches(password,hash);Problem.require(match&&!rows.isEmpty(),401,"BAD_CREDENTIALS","账号或密码错误");
  var u=rows.getFirst();Problem.require(Boolean.TRUE.equals(u.get("enabled")),403,"ACCOUNT_DISABLED","账号已停用");
  long id=((Number)u.get("id")).longValue();String jti=UUID.randomUUID().toString();
  db.update("INSERT INTO login_session(jti,user_id,expires_at) VALUES(?,?,?)",jti,id,Data.ts(Instant.now().plusSeconds(900)));
  return Map.of("accessToken",tokens.issue(id,jti),"expiresIn",900,"user",user(u));
 }
 private Map<String,Object> user(Map<String,Object> u){
  var roles=db.queryForList("SELECT role_code FROM user_role WHERE user_id=? ORDER BY role_code",String.class,u.get("id"));
  var communities=db.queryForList("SELECT community_id FROM community_admin WHERE user_id=? ORDER BY community_id",Long.class,u.get("id")).stream().map(Object::toString).toList();
  return Map.of("id",u.get("id").toString(),"username",u.get("username"),"roles",roles,"communities",communities,"permissions",Actor.permissions(new HashSet<>(roles)));
 }
 @PostMapping("/api/v1/auth/register") public Map<String,Object> register(@RequestBody Map<String,Object> input,HttpServletRequest req){
  Problem.require(Set.of("username","password").containsAll(input.keySet()),422,"INVALID_ARGUMENT","注册只接受账号和密码");
  String username=Data.str(input,"username").strip().toLowerCase(Locale.ROOT),password=Data.str(input,"password");
  Problem.require(username.matches("[a-z0-9_]{4,40}")&&password.length()>=10&&password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=72,422,"INVALID_ARGUMENT","账号4至40位字母数字下划线；密码至少10字符且不超过72字节");
  Long count=redis.execute(LoginLimit.SCRIPT,List.of("register:"+Data.hash(req.getRemoteAddr())),"60000");
  Problem.require(count!=null&&count<=10,429,"REGISTER_RATE_LIMIT","注册过于频繁，请稍后重试");
  String hash=encoder.encode(password);
  return tx.execute(s->{long id=Data.id(db);db.update("INSERT INTO app_user(id,username,password_hash,role) VALUES(?,?,?,'DRIVER')",id,username,hash);db.update("INSERT INTO user_role VALUES(?,'DRIVER')",id);Management.audit(db,new Actor(id,Set.of("DRIVER"),Set.of()),"REGISTER",id,Map.of());return Map.of("id",Long.toString(id),"username",username,"roles",List.of("DRIVER"));});
 }
 public Map<String,Object> state(String bearer){
  var c=tokens.verify(bearer);var rows=db.queryForList("SELECT u.* FROM login_session s JOIN app_user u ON u.id=s.user_id WHERE s.jti=? AND s.user_id=? AND s.revoked=FALSE AND s.expires_at>UTC_TIMESTAMP(3) AND u.enabled=TRUE",c.getJWTID(),c.getSubject());
  Problem.require(!rows.isEmpty(),401,"SESSION_REVOKED","登录已失效");return user(rows.getFirst());
 }
 @GetMapping("/internal/v1/auth/state") public Map<String,Object> internal(@RequestHeader("Authorization")String bearer){return state(bearer);}
 @GetMapping("/api/v1/auth/me") public Map<String,Object> me(@RequestHeader("Authorization")String bearer){return state(bearer);}
 @PostMapping("/api/v1/auth/logout") public Map<String,Object> logout(@RequestHeader("Authorization")String bearer){db.update("UPDATE login_session SET revoked=TRUE WHERE jti=?",tokens.verify(bearer).getJWTID());return Map.of("loggedOut",true);}
}
