package cn.community.identity;

import cn.community.foundation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@RestController @Profile("business")
public class IdentityAdmin {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final RestClient parking;private final String serviceToken;
 public IdentityAdmin(JdbcTemplate db,PlatformTransactionManager manager,@Value("${business.parking-url:http://127.0.0.1:9082}")String url,@Value("${business.service-token}")String token){this.db=db;tx=new TransactionTemplate(manager);serviceToken=token;var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(2000);factory.setReadTimeout(2000);parking=RestClient.builder().baseUrl(url).requestFactory(factory).build();}
 private void guard(Actor actor){
  db.queryForObject("SELECT id FROM authorization_guard WHERE id=1 FOR UPDATE",Integer.class);
  Problem.require(db.queryForObject("SELECT COUNT(*) FROM app_user u JOIN user_role r ON r.user_id=u.id WHERE u.id=? AND u.enabled=TRUE AND r.role_code='PLATFORM'",Integer.class,actor.id())==1,403,"FORBIDDEN","当前账号没有授权管理权限");
 }
 private void lastAdmin(){Problem.require(db.queryForObject("SELECT COUNT(*) FROM app_user u JOIN user_role r ON r.user_id=u.id WHERE u.enabled=TRUE AND r.role_code='PLATFORM'",Integer.class)>0,409,"LAST_ADMIN","必须保留至少一名启用的平台管理员");}
 private void revoke(long id){db.update("UPDATE login_session SET revoked=TRUE WHERE user_id=?",id);}
 @GetMapping("/api/v1/admin/users") public Map<String,Object> users(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,HttpServletRequest req){
  Actor.of(req).permission("USER_MANAGE");var result=Management.page(db,"id,username,enabled","FROM app_user","id",page,size);
  @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)result.get("items");
  for(var item:items){item.put("roles",db.queryForList("SELECT role_code FROM user_role WHERE user_id=? ORDER BY role_code",String.class,item.get("id")));item.put("communities",db.queryForList("SELECT community_id FROM community_admin WHERE user_id=? ORDER BY community_id",Long.class,item.get("id")).stream().map(Object::toString).toList());}return result;
 }
 @PatchMapping("/api/v1/admin/users/{id}/status") public Map<String,Object> status(@PathVariable long id,@RequestBody Map<String,Object> in,HttpServletRequest req){Actor a=Actor.of(req);a.permission("USER_MANAGE");boolean enabled=Management.enabled(in);return tx.execute(s->{guard(a);Management.one(db,"SELECT id FROM app_user WHERE id=? FOR UPDATE",id);db.update("UPDATE app_user SET enabled=? WHERE id=?",enabled,id);lastAdmin();revoke(id);Management.audit(db,a,"USER_STATUS",id,Map.of("enabled",enabled));return Map.of("id",Long.toString(id),"enabled",enabled);});}
 @PutMapping("/api/v1/admin/users/{id}/roles") public Map<String,Object> roles(@PathVariable long id,@RequestBody Map<String,Object> in,HttpServletRequest req){
  Actor a=Actor.of(req);a.permission("USER_MANAGE");Object value=in.get("roles");Problem.require(value instanceof List<?>,422,"INVALID_ARGUMENT","roles必须为角色数组");Set<String> roles=new TreeSet<>();for(Object v:(List<?>)value)roles.add(Objects.toString(v,""));Problem.require(!roles.isEmpty()&&Set.of("DRIVER","OWNER","PROPERTY","PLATFORM").containsAll(roles),422,"INVALID_ARGUMENT","角色无效");
  return tx.execute(s->{guard(a);Management.one(db,"SELECT id FROM app_user WHERE id=? FOR UPDATE",id);db.update("DELETE FROM user_role WHERE user_id=?",id);for(String role:roles)db.update("INSERT INTO user_role VALUES(?,?)",id,role);if(!roles.contains("PROPERTY"))db.update("DELETE FROM community_admin WHERE user_id=?",id);lastAdmin();revoke(id);Management.audit(db,a,"USER_ROLES",id,Map.of("roles",roles));return Map.of("id",Long.toString(id),"roles",roles);});
 }
 @GetMapping("/api/v1/admin/communities/{id}/administrators") public Map<String,Object> administrators(@PathVariable long id,HttpServletRequest req){Actor.of(req).permission("COMMUNITY_AUTHORIZE");return Map.of("userIds",db.queryForList("SELECT user_id FROM community_admin WHERE community_id=? ORDER BY user_id",Long.class,id).stream().map(Object::toString).toList());}
 @PutMapping("/api/v1/admin/communities/{id}/administrators") public Map<String,Object> communities(@PathVariable long id,@RequestBody Map<String,Object> in,HttpServletRequest req){
  Actor a=Actor.of(req);a.permission("COMMUNITY_AUTHORIZE");Object value=in.get("userIds");Problem.require(value instanceof List<?> && ((List<?>)value).size()<=100,422,"INVALID_ARGUMENT","userIds必须为至多100个用户ID");Set<Long> ids=new TreeSet<>();for(Object v:(List<?>)value)ids.add(Long.parseLong(v.toString()));
  try{parking.get().uri("/internal/v1/communities/"+id).header("X-Service-Token",serviceToken).retrieve().toBodilessEntity();}catch(org.springframework.web.client.HttpClientErrorException.NotFound e){throw new Problem(404,"NOT_FOUND","社区不存在");}catch(Exception e){throw new Problem(503,"PARKING_UNAVAILABLE","无法核验社区，授权未变更");}
  return tx.execute(s->{guard(a);for(long userId:ids)Problem.require(db.queryForObject("SELECT COUNT(*) FROM app_user u JOIN user_role r ON r.user_id=u.id WHERE u.id=? AND u.enabled=TRUE AND r.role_code='PROPERTY'",Integer.class,userId)==1,422,"INVALID_PROPERTY","只能授权启用的物业账号");Set<Long> affected=new HashSet<>(db.queryForList("SELECT user_id FROM community_admin WHERE community_id=?",Long.class,id));affected.addAll(ids);db.update("DELETE FROM community_admin WHERE community_id=?",id);for(long userId:ids)db.update("INSERT INTO community_admin VALUES(?,?)",userId,id);affected.forEach(this::revoke);Management.audit(db,a,"COMMUNITY_AUTHORIZE",id,Map.of("userIds",ids));return Map.of("communityId",Long.toString(id),"userIds",ids.stream().map(Object::toString).toList());});
 }
 @GetMapping("/api/v1/admin/audit-logs/identity") public Map<String,Object> audit(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,HttpServletRequest req){Actor.of(req).permission("AUDIT_READ");return Management.page(db,"id,actor_id,action,resource_id,created_at,detail_json","FROM audit_log","id DESC",page,size);}
}
