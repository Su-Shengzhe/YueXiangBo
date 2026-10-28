package cn.community.foundation;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared validation and explicit, bounded management list responses. */
public final class Management {
 private Management() {}
 public static String text(Map<String,?> input,String key,int max){String value=Data.str(input,key).strip();Problem.require(!value.isEmpty()&&value.length()<=max,422,"INVALID_ARGUMENT",key+"不能为空且不能超过"+max+"字符");return value;}
 public static boolean enabled(Map<String,?> input){Object value=input.get("enabled");Problem.require(value instanceof Boolean,422,"INVALID_ARGUMENT","enabled必须是布尔值");return Boolean.TRUE.equals(value);}
 public static void version(Map<String,?> input,Map<String,Object> row){Problem.require(Data.number(input,"version")==((Number)row.get("version")).longValue(),409,"STALE_VERSION","记录已变化，请刷新后重试");}
 public static Map<String,Object> one(JdbcTemplate db,String sql,Object...args){var rows=db.queryForList(sql,args);Problem.require(!rows.isEmpty(),404,"NOT_FOUND","记录不存在");return rows.getFirst();}
 public static Map<String,Object> page(JdbcTemplate db,String columns,String from,String order,int page,int size,Object...args){
  Problem.require(page>=1&&page<=100000&&size>=1&&size<=100,422,"INVALID_PAGE","页码从1开始，每页1至100条");
  long total=db.queryForObject("SELECT COUNT(*) "+from,Long.class,args);var values=new ArrayList<Object>(Arrays.asList(args));values.add(size);values.add((page-1)*size);
  var items=db.queryForList("SELECT "+columns+" "+from+" ORDER BY "+order+" LIMIT ? OFFSET ?",values.toArray()).stream().map(Data::row).toList();
  return Map.of("items",items,"total",total,"page",page,"size",size);
 }
 public static void audit(JdbcTemplate db,Actor actor,String action,long id,Object detail){db.update("INSERT INTO audit_log(actor_id,action,resource_id,detail_json) VALUES(?,?,?,?)",actor.id(),action,Long.toString(id),Data.json(detail));}
}
