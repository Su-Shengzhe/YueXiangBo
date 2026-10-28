package cn.community.foundation;
import java.util.*;
import java.time.*;
import java.sql.Timestamp;
import java.math.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
public final class Data {
 public static final JsonMapper JSON=JsonMapper.builder().build();
 /** IDs are scoped to a service database; the counter rolls back with the caller transaction. */
 public static long id(JdbcTemplate db){
  if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("ID allocation requires a transaction");
  long last=db.queryForObject("SELECT last_id FROM business_sequence WHERE name='business' FOR UPDATE",Long.class);
  long next=Math.addExact(last,1);
  db.update("UPDATE business_sequence SET last_id=? WHERE name='business'",next);
  return next;
 }
 public static String json(Object v){return JSON.writeValueAsString(v);}
 @SuppressWarnings("unchecked") public static Map<String,Object> map(String v){return JSON.readValue(v,Map.class);}
 public static String str(Map<String,?> m,String k){Object v=m.get(k);return v==null?"":v.toString();}
 public static long number(Map<String,?> m,String k){try{return Long.parseLong(str(m,k));}catch(Exception e){throw new Problem(422,"INVALID_ARGUMENT","字段 "+k+" 必须是整数");}}
 public static Instant instant(Object v){if(v instanceof Timestamp t)return t.toInstant();if(v instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC);return Instant.parse(v.toString());}
 public static Timestamp ts(Instant v){return Timestamp.from(v);}
 public static Map<String,Object> row(Map<String,Object> row){
  Map<String,Object> result=new LinkedHashMap<>();
  row.forEach((key,value)->{Object v=value;String[] parts=key.toLowerCase(Locale.ROOT).split("_");StringBuilder b=new StringBuilder(parts[0]);for(int i=1;i<parts.length;i++)b.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));String k=b.toString();
   if(v!=null&&(k.equals("id")||k.endsWith("Id")))v=v.toString();
   if(v instanceof Timestamp||v instanceof LocalDateTime)v=instant(v).toString();
   result.put(k,v);
  });return result;
 }
 public static long fee(long hourly,long seconds){
  Problem.require(hourly>0&&seconds>=0,422,"INVALID_ARGUMENT","计费参数无效");
  return fee(hourly, Duration.ofSeconds(seconds));
 }
 public static long fee(long hourly, Duration duration){
  Problem.require(hourly>0 && !duration.isNegative(),422,"INVALID_ARGUMENT","计费参数无效");
  long units=duration.getSeconds()/1800;
  if(duration.getSeconds()%1800!=0 || duration.getNano()!=0) units++;
  return BigDecimal.valueOf(hourly).multiply(BigDecimal.valueOf(units))
   .divide(BigDecimal.valueOf(2),0,RoundingMode.HALF_UP).longValueExact();
 }
 public static String hash(String v){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(v.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
