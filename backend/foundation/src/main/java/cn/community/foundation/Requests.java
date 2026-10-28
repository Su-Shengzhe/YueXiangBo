package cn.community.foundation;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
public final class Requests {
 public static String key(String key){Problem.require(key!=null&&key.matches("[A-Za-z0-9_-]{8,80}"),422,"INVALID_IDEMPOTENCY_KEY","需要 8—80 位幂等键");return key;}
 public static Map<String,Object> existing(JdbcTemplate db,long actor,String operation,String key,String hash){
  key(key);var rows=db.queryForList("SELECT request_hash,response_json FROM idempotency_record WHERE actor_id=? AND operation=? AND request_key=?",actor,operation,key);
  if(rows.isEmpty())return null;Problem.require(hash.equals(rows.getFirst().get("request_hash")),409,"IDEMPOTENCY_CONFLICT","同一请求键不能用于不同参数");return Data.map(rows.getFirst().get("response_json").toString());
 }
 public static void save(JdbcTemplate db,long actor,String operation,String key,String hash,Map<String,Object> response){db.update("INSERT INTO idempotency_record(actor_id,operation,request_key,request_hash,response_json) VALUES(?,?,?,?,?)",actor,operation,key,hash,Data.json(response));}
}
