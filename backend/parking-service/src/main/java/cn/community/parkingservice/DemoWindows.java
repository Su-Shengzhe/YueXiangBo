package cn.community.parkingservice;

import cn.community.foundation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("business")
public class DemoWindows {
 private final JdbcTemplate db;
 private final BusinessClock clock;
 private final TransactionTemplate tx;
 public DemoWindows(JdbcTemplate db, BusinessClock clock, PlatformTransactionManager manager) {
  this.db=db; this.clock=clock; this.tx=new TransactionTemplate(manager);
 }
 @PostMapping("/api/v1/demo/windows")
 public Map<String,Object> prepare(@RequestParam(defaultValue="20") long spaceId, HttpServletRequest request) {
  Actor actor=Actor.of(request); actor.permission("DEMO_MANAGE");
  Problem.require(clock.isDemo(),403,"DEMO_DISABLED","仅本地演示允许准备窗口");
  Problem.require(spaceId==20,403,"DEMO_RESOURCE_ONLY","演示补窗只用于原始种子车位，新车位请由业主管理窗口");
  return tx.execute(status -> {
   var parent=Management.one(db,"SELECT community_id FROM parking_space WHERE id=?",spaceId);
   var community=Management.one(db,"SELECT enabled FROM community WHERE id=? FOR UPDATE",parent.get("community_id"));
   Problem.require(Boolean.TRUE.equals(community.get("enabled")),409,"COMMUNITY_DISABLED","社区已停用");
   var spaces=db.queryForList("SELECT id,enabled,hourly_fen,review_status FROM parking_space WHERE id=? FOR UPDATE",spaceId);
   Problem.require(!spaces.isEmpty(),404,"NOT_FOUND","车位不存在");
   Problem.require(Boolean.TRUE.equals(spaces.getFirst().get("enabled")),409,"SPACE_DISABLED","车位不可用");
   Problem.require("APPROVED".equals(spaces.getFirst().get("review_status")),409,"SPACE_NOT_APPROVED","车位未审核通过");
   Instant now=clock.now();
   long start=((now.getEpochSecond()+900+899)/900)*900;
   long end=((now.getEpochSecond()+30*86400)/900)*900;
   var rows=db.queryForList("SELECT MAX(end_at) AS last_end FROM share_window WHERE space_id=?",spaceId);
   Object last=rows.getFirst().get("last_end");
   if(last!=null){Instant boundary=Data.instant(last); start=Math.max(start,((boundary.getEpochSecond()+(boundary.getNano()>0?1:0)+899)/900)*900);}
   if(start>=end)return Map.of("created",false,"spaceId",Long.toString(spaceId));
   long id=Data.id(db);
   db.update("INSERT INTO share_window(id,space_id,start_at,end_at,hourly_fen) VALUES(?,?,?,?,?)",id,spaceId,Data.ts(Instant.ofEpochSecond(start)),Data.ts(Instant.ofEpochSecond(end)),spaces.getFirst().get("hourly_fen"));
   db.update("INSERT INTO audit_log(actor_id,action,resource_id) VALUES(?,'DEMO_WINDOW_PREPARE',?)",actor.id(),Long.toString(id));
   return Map.of("created",true,"spaceId",Long.toString(spaceId),"windowId",Long.toString(id),"startAt",Instant.ofEpochSecond(start).toString(),"endAt",Instant.ofEpochSecond(end).toString());
  });
 }
}
