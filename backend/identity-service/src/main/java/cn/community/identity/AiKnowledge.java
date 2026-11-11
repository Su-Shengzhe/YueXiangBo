package cn.community.identity;
import cn.community.foundation.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController @Profile("business")
public class AiKnowledge {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final BusinessClock clock;
 public AiKnowledge(JdbcTemplate db,PlatformTransactionManager manager,BusinessClock clock){this.db=db;tx=new TransactionTemplate(manager);this.clock=clock;}
 private Actor admin(HttpServletRequest r){var a=Actor.of(r);a.permission("KNOWLEDGE_MANAGE");return a;}
 @GetMapping("/api/v1/admin/knowledge") public Map<String,Object> list(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,HttpServletRequest request){admin(request);return Management.page(db,"*","FROM knowledge_entry","id DESC",page,size);}
 private Map<String,Object> fields(Map<String,Object> in){
  String rule=Management.text(in,"ruleId",64);Problem.require(rule.matches("[A-Z0-9_]+"),422,"INVALID_RULE_ID","规则编号须为大写字母数字或下划线");
  long community=Data.number(in,"communityId"),version=Data.number(in,"ruleVersion");Problem.require(community>=0&&version>0&&version<=Integer.MAX_VALUE,422,"INVALID_ARGUMENT","规则版本或社区无效");
  Instant from=Instant.parse(Management.text(in,"effectiveAt",40));String end=Data.str(in,"expiresAt");Instant until=end.isBlank()?null:Instant.parse(end);Problem.require(until==null||until.isAfter(from),422,"INVALID_RANGE","结束须晚于开始");
  var row=new LinkedHashMap<String,Object>();row.put("rule",rule);row.put("community",community);row.put("ruleVersion",version);row.put("title",Management.text(in,"title",128));row.put("topic",Management.text(in,"topic",40));row.put("keywords",Management.text(in,"keywords",500));row.put("content",Management.text(in,"content",2000));row.put("from",Data.ts(from));row.put("until",until==null?null:Data.ts(until));return row;
 }
 @PostMapping("/api/v1/admin/knowledge") public Map<String,Object> create(@RequestBody Map<String,Object> in,HttpServletRequest request){var a=admin(request);var f=fields(in);return tx.execute(s->{
  db.queryForObject("SELECT id FROM authorization_guard WHERE id=1 FOR UPDATE",Integer.class);
  if(db.queryForObject("SELECT COUNT(*) FROM knowledge_rule WHERE rule_id=? AND community_id=?",Integer.class,f.get("rule"),f.get("community"))==0)db.update("INSERT INTO knowledge_rule VALUES(?,?)",f.get("rule"),f.get("community"));
  db.update("INSERT INTO knowledge_entry(rule_id,community_id,rule_version,title,topic,keywords,content,effective_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?)",f.get("rule"),f.get("community"),f.get("ruleVersion"),f.get("title"),f.get("topic"),f.get("keywords"),f.get("content"),f.get("from"),f.get("until"));
  long id=db.queryForObject("SELECT MAX(id) FROM knowledge_entry",Long.class);Management.audit(db,a,"KNOWLEDGE_CREATE",id,Map.of());return Data.row(Management.one(db,"SELECT * FROM knowledge_entry WHERE id=?",id));});}
 @PatchMapping("/api/v1/admin/knowledge/{id}") public Map<String,Object> edit(@PathVariable long id,@RequestBody Map<String,Object> in,HttpServletRequest request){var a=admin(request);return tx.execute(s->{var row=Management.one(db,"SELECT * FROM knowledge_entry WHERE id=? FOR UPDATE",id);Management.version(in,row);Problem.require("DRAFT".equals(row.get("status")),409,"IMMUTABLE_RULE","已发布条款不可覆盖，请创建新版本");db.update("UPDATE knowledge_entry SET title=?,keywords=?,content=?,version=version+1 WHERE id=?",Management.text(in,"title",128),Management.text(in,"keywords",500),Management.text(in,"content",2000),id);Management.audit(db,a,"KNOWLEDGE_EDIT",id,Map.of());return Data.row(Management.one(db,"SELECT * FROM knowledge_entry WHERE id=?",id));});}
 @PostMapping("/api/v1/admin/knowledge/{id}/{action}") public Map<String,Object> publish(@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object> in,HttpServletRequest request){var a=admin(request);Problem.require(Set.of("publish","disable").contains(action),404,"NOT_FOUND","操作不存在");return tx.execute(s->{
  var initial=Management.one(db,"SELECT * FROM knowledge_entry WHERE id=?",id);
  Management.one(db,"SELECT * FROM knowledge_rule WHERE rule_id=? AND community_id=? FOR UPDATE",initial.get("rule_id"),initial.get("community_id"));
  var row=Management.one(db,"SELECT * FROM knowledge_entry WHERE id=? FOR UPDATE",id);Management.version(in,row);
  if(action.equals("publish")){
   Problem.require("DRAFT".equals(row.get("status")),409,"IMMUTABLE_RULE","仅草稿可发布");
   var others=db.queryForList("SELECT * FROM knowledge_entry WHERE rule_id=? AND community_id=? AND status='PUBLISHED' AND id<>?",row.get("rule_id"),row.get("community_id"),id);
   Instant from=Data.instant(row.get("effective_at")),until=row.get("expires_at")==null?Instant.MAX:Data.instant(row.get("expires_at"));
   for(var other:others){Instant aFrom=Data.instant(other.get("effective_at")),aUntil=other.get("expires_at")==null?Instant.MAX:Data.instant(other.get("expires_at"));Problem.require(!from.isBefore(aUntil)||!aFrom.isBefore(until),409,"RULE_OVERLAP","同规则同社区版本有效期不能重叠");}
  }
  db.update("UPDATE knowledge_entry SET status=?,version=version+1 WHERE id=?",action.equals("publish")?"PUBLISHED":"DISABLED",id);Management.audit(db,a,"KNOWLEDGE_"+action.toUpperCase(Locale.ROOT),id,Map.of());return Data.row(Management.one(db,"SELECT * FROM knowledge_entry WHERE id=?",id));});}
 public List<Map<String,Object>> retrieve(String query,long community){
  Problem.require(query!=null&&!query.isBlank()&&query.length()<=500&&community>=0,422,"INVALID_ARGUMENT","规则查询无效");
  var rows=db.queryForList("SELECT * FROM knowledge_entry WHERE status='PUBLISHED' AND community_id IN (0,?) AND effective_at<=? AND (expires_at IS NULL OR expires_at>?) ORDER BY id",community,Data.ts(clock.now()),Data.ts(clock.now()));
  return rows.stream().filter(r->score(query,r)>0).sorted(Comparator.<Map<String,Object>>comparingInt(r->score(query,r)).reversed()).limit(5).map(r->Map.<String,Object>of("ruleId",r.get("rule_id"),"version",r.get("rule_version"),"communityId",r.get("community_id").toString(),"title",r.get("title"),"clause",r.get("content"))).toList();
 }
 static int score(String q,Map<String,Object> r){
  int score=0;for(String keyword:r.get("keywords").toString().split("\\s+"))if(!keyword.isBlank()&&q.contains(keyword))score+=10;
  String content=r.get("content").toString();var seen=new HashSet<String>();
  for(int i=0;i+1<q.length();i++){String term=q.substring(i,i+2);if(term.matches("[\\p{IsHan}]{2}")&&seen.add(term)&&content.contains(term))score++;}
  return score;
 }
}
