package cn.community.foundation;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
public record Actor(long id, Set<String> roles, Set<Long> communities) {
 public static Actor of(HttpServletRequest req){Actor a=(Actor)req.getAttribute("actor");if(a==null)throw new Problem(401,"UNAUTHENTICATED","请先登录");return a;}
 public void role(String role){Problem.require(roles.contains(role),403,"FORBIDDEN","没有此操作权限");}
 public static Set<String> permissions(Set<String> roles){
  Set<String> result=new HashSet<>();
  if(roles.contains("PLATFORM"))result.addAll(Set.of("USER_MANAGE","COMMUNITY_MANAGE","COMMUNITY_AUTHORIZE","SPACE_MODERATE","AUDIT_READ","PARKING_OPERATE","DEMO_MANAGE"));
  if(roles.contains("PROPERTY"))result.addAll(Set.of("SPACE_REVIEW","PARKING_OPERATE"));
  return result;
 }
 public void permission(String permission){Problem.require(permissions(roles).contains(permission),403,"FORBIDDEN","没有此操作权限");}
 public void community(long community){Problem.require(roles.contains("PLATFORM")||(roles.contains("PROPERTY")&&communities.contains(community)),403,"FORBIDDEN","无此社区操作权限");}
 public static Actor from(Map<String,Object> m){Set<String> roles=new HashSet<>();for(Object v:(List<?>)m.get("roles"))roles.add(v.toString());Set<Long> communities=new HashSet<>();for(Object v:(List<?>)m.get("communities"))communities.add(Long.parseLong(v.toString()));return new Actor(Long.parseLong(m.get("id").toString()),roles,communities);}
}
