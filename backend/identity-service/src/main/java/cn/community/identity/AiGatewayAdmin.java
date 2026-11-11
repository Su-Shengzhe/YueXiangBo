package cn.community.identity;

import cn.community.foundation.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

/** Immutable provider revisions, a serialized route pointer, and persistent budget reservations. */
@RestController @Profile("business")
public class AiGatewayAdmin {
 @org.springframework.beans.factory.annotation.Value("${llm.credentials-directory:deploy/secrets}") String credentialsDirectory="deploy/secrets";
 private final JdbcTemplate db;
 private final TransactionTemplate tx;
 public AiGatewayAdmin(JdbcTemplate db,PlatformTransactionManager manager){this.db=db;tx=new TransactionTemplate(manager);}
 public Map<String,Object> provider(long id){var row=Data.row(Management.one(db,"SELECT * FROM ai_provider_revision WHERE id=?",id));Problem.require(Boolean.TRUE.equals(row.get("enabled")),409,"PROVIDER_DISABLED","配置已停用");row.put("routeVersion",-id);return row;}
 String credentialFingerprint(Map<String,Object> row){return AiProviderFingerprint.fingerprint(credentialsDirectory,Data.str(row,"credentialRef"));}
 void checked(long id,String fingerprint,Actor actor){tx.executeWithoutResult(s->{Management.one(db,"SELECT id FROM ai_route_state WHERE id=1 FOR UPDATE");var row=provider(id);Problem.require(fingerprint.equals(credentialFingerprint(row)),409,"CREDENTIAL_CHANGED","检查期间凭据已改变");db.update("UPDATE ai_provider_revision SET checked_at=?,checked_fingerprint=? WHERE id=?",Data.ts(java.time.Instant.now()),fingerprint,id);Management.audit(db,actor,"AI_PROVIDER_CHECK",id,Map.of("passed",true));});}
 private Actor admin(HttpServletRequest request){var a=Actor.of(request);a.role("PLATFORM");a.permission("MODEL_MANAGE");return a;}
 @GetMapping("/api/v1/admin/ai-gateway")
 public Map<String,Object> state(HttpServletRequest request){admin(request);return view();}
 private Map<String,Object> view(){return Map.of("route",Data.row(Management.one(db,"SELECT * FROM ai_route_state WHERE id=1")),
   "providers",db.queryForList("SELECT * FROM ai_provider_revision ORDER BY id DESC LIMIT 100").stream().map(Data::row).toList(),
   "budget",Data.row(Management.one(db,"SELECT * FROM ai_budget WHERE id=1")),
   "paidBudgets",db.queryForList("SELECT scope,supplier_host,limit_microyuan,approved FROM ai_paid_budget ORDER BY scope").stream().map(Data::row).toList(),
   "history",db.queryForList("SELECT * FROM ai_route_history ORDER BY version DESC LIMIT 100").stream().map(Data::row).toList());}
 @PostMapping("/api/v1/admin/ai-gateway/providers")
 public Map<String,Object> create(@RequestBody Map<String,Object> input,HttpServletRequest request){
  var actor=admin(request);
  String provider=Management.text(input,"providerName",80),protocol=Management.text(input,"protocol",40),
    url=Management.text(input,"baseUrl",500),model=Management.text(input,"modelName",128),credential=Management.text(input,"credentialRef",80);
  Problem.require(protocol.equals("OPENAI_CHAT"),422,"UNSUPPORTED_PROTOCOL","当前仅支持OpenAI兼容聊天协议");
  try{var uri=URI.create(url);Problem.require("https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getQuery()==null&&uri.getFragment()==null,422,"INVALID_URL","供应商地址须为HTTPS且不含凭据或查询参数");}
  catch(IllegalArgumentException e){throw new Problem(422,"INVALID_URL","供应商地址无效");}
  Problem.require(credential.matches("[a-zA-Z0-9_-]{1,60}\\.properties"),422,"INVALID_CREDENTIAL_REF","凭据文件名无效");
  return tx.execute(s->{
   // The stable guard serializes generated ID retrieval with other configuration writers.
   db.queryForObject("SELECT id FROM ai_route_state WHERE id=1 FOR UPDATE",Integer.class);
   db.update("INSERT INTO ai_provider_revision(provider_name,protocol,base_url,model_name,credential_ref,created_by) VALUES(?,?,?,?,?,?)",provider,protocol,url,model,credential,actor.id());
   long id=db.queryForObject("SELECT MAX(id) FROM ai_provider_revision",Long.class);
   String host=URI.create(url).getHost().toLowerCase(Locale.ROOT).replaceFirst("[.]$","");
   var budgets=db.queryForList("SELECT * FROM ai_paid_budget WHERE supplier_host=?",host);
   boolean wantsPaid="PAID".equals(input.get("billingMode"));
   boolean newScope=wantsPaid&&budgets.isEmpty();
   if(newScope){
    long limit=Data.number(input,"paidLimitMicroyuan");
    var stage=Management.one(db,"SELECT limit_microyuan FROM ai_budget WHERE id=1 FOR UPDATE");
    Problem.require(Boolean.TRUE.equals(input.get("paidApproved"))&&limit>0&&limit<=Data.number(stage,"limit_microyuan"),422,"INVALID_PAID_BUDGET","须明确授权供应商额度，且不能超过阶段上限");
    db.update("INSERT INTO ai_paid_budget(scope,supplier_host,limit_microyuan,approved) VALUES(?,?,?,TRUE)","SUP-"+id,host,limit);
    budgets=db.queryForList("SELECT * FROM ai_paid_budget WHERE supplier_host=?",host);
   }
   var paid=budgets.isEmpty()?null:budgets.getFirst();
   Problem.require(paid==null||!"FREE".equals(input.get("billingMode")),422,"PAID_SCOPE_REQUIRED","此供应商须使用已授权额度");
   long inputPrice=input.containsKey("inputPriceMicroyuanPerMillion")?Data.number(input,"inputPriceMicroyuanPerMillion"):400000;
   long outputPrice=input.containsKey("outputPriceMicroyuanPerMillion")?Data.number(input,"outputPriceMicroyuanPerMillion"):1200000;
   Problem.require(inputPrice>0&&outputPrice>0&&inputPrice<=1000000000&&outputPrice<=1000000000,422,"INVALID_PRICE","计量价格超出范围");
   if(paid!=null){
    Problem.require(Boolean.TRUE.equals(paid.get("approved")),422,"PAID_SCOPE_UNAPPROVED","供应商尚未授权");
    Problem.require(inputPrice>=Data.number(paid,"min_input_price")&&outputPrice>=Data.number(paid,"min_output_price"),422,"PRICE_TOO_LOW","价格低于供应商预算基准");
    if(wantsPaid&&input.containsKey("paidLimitMicroyuan"))Problem.require(Data.number(input,"paidLimitMicroyuan")==Data.number(paid,"limit_microyuan"),409,"PAID_LIMIT_CONFLICT","同一供应商的配置版本共享原有额度");
   }
   // Earlier clients omit billing fields when creating the original free Agnes configuration.
   boolean legacyFree=!input.containsKey("billingMode")&&!input.containsKey("priceVersion")&&url.equals("https://apihub.agnes-ai.com/v1")&&model.equals("agnes-2.5-flash");
   String priceVersion=legacyFree?"agnes-list-usd-fx8-v1":Management.text(input,"priceVersion",80);
   long outputLimit=input.containsKey("maxOutputTokens")?Data.number(input,"maxOutputTokens"):AiModelCalls.DEFAULT_OUTPUT_TOKENS;
   Problem.require(outputLimit>=1&&outputLimit<=32768,422,"INVALID_OUTPUT_LIMIT","输出上限须在1至32768之间");
   boolean disable=input.containsKey("disableThinking")?Boolean.TRUE.equals(input.get("disableThinking")):paid!=null&&Boolean.TRUE.equals(paid.get("disable_thinking"));
   db.update("UPDATE ai_provider_revision SET input_price_microyuan_per_million=?,output_price_microyuan_per_million=?,price_version=?,free_access_approved=?,paid_scope=?,disable_thinking=?,max_output_tokens=? WHERE id=?",inputPrice,outputPrice,priceVersion,paid==null&&(legacyFree||Boolean.TRUE.equals(input.get("freeAccessApproved"))),paid==null?null:paid.get("scope"),disable,outputLimit,id);
   Management.audit(db,actor,"AI_PROVIDER_CREATE",id,Map.of("provider",provider,"model",model));
   return Data.row(Management.one(db,"SELECT * FROM ai_provider_revision WHERE id=?",id));
  });
 }
 @PostMapping("/api/v1/admin/ai-gateway/publish")
 public Map<String,Object> publish(@RequestBody Map<String,Object> input,HttpServletRequest request){return change(input,admin(request),false);}
 @PostMapping("/api/v1/admin/ai-gateway/rollback")
 public Map<String,Object> rollback(@RequestBody Map<String,Object> input,HttpServletRequest request){return change(input,admin(request),true);}
 private Map<String,Object> change(Map<String,Object> input,Actor actor,boolean rollback){return tx.execute(s->{
  var current=Management.one(db,"SELECT * FROM ai_route_state WHERE id=1 FOR UPDATE");Management.version(input,current);
  Object previous=current.get("previous_revision_id");
  Problem.require(!rollback||previous!=null,409,"NO_PREVIOUS_ROUTE","尚无可回退配置");
  long id=rollback?((Number)previous).longValue():Data.number(input,"providerRevisionId");
  var provider=Management.one(db,"SELECT * FROM ai_provider_revision WHERE id=?",id);
  Problem.require(Boolean.TRUE.equals(provider.get("enabled")),409,"PROVIDER_DISABLED","配置已停用");
  Problem.require(provider.get("checked_at")!=null&&Data.instant(provider.get("checked_at")).isAfter(java.time.Instant.now().minusSeconds(600))&&credentialFingerprint(Data.row(provider)).equals(provider.get("checked_fingerprint")),409,"PROVIDER_NOT_VERIFIED","请先完成配置检查，检查结果有效期10分钟");
  var stage=Management.one(db,"SELECT * FROM ai_budget WHERE id=1 FOR UPDATE");
  long needed=AiModelCalls.reservation(Data.number(provider,"input_price_microyuan_per_million"),Data.number(provider,"output_price_microyuan_per_million"),Data.number(provider,"max_output_tokens"));
  Problem.require(!Boolean.TRUE.equals(stage.get("paused"))&&needed<=Data.number(stage,"limit_microyuan")-Data.number(stage,"spent_microyuan")-Data.number(stage,"reserved_microyuan"),429,"AI_BUDGET_UNAVAILABLE","阶段额度不足或已暂停");
  verifyHostScope(provider);
  if(provider.get("paid_scope")!=null)requirePaidCapacity(provider.get("paid_scope"),needed);
  else Problem.require(Boolean.TRUE.equals(provider.get("free_access_approved"))&&Boolean.TRUE.equals(stage.get("free_access_confirmed")),409,"AI_PRICING_UNCONFIRMED","免费调用尚未确认");
  long version=((Number)current.get("version")).longValue()+1;
  db.update("INSERT INTO ai_route_history(version,provider_revision_id,actor_id) VALUES(?,?,?)",version,id,actor.id());
  db.update("UPDATE ai_route_state SET version=?,previous_revision_id=provider_revision_id,provider_revision_id=? WHERE id=1",version,id);
  Management.audit(db,actor,rollback?"AI_ROUTE_ROLLBACK":"AI_ROUTE_PUBLISH",version,Map.of("providerRevisionId",Long.toString(id)));
  return view();
 });}
 @PatchMapping("/api/v1/admin/ai-gateway/providers/{id}/status")
 public Map<String,Object> status(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest request){var actor=admin(request);boolean enabled=Management.enabled(input);return tx.execute(s->{
  var current=Management.one(db,"SELECT * FROM ai_route_state WHERE id=1 FOR UPDATE");Management.one(db,"SELECT id FROM ai_provider_revision WHERE id=?",id);
  Problem.require(enabled||!Objects.equals(current.get("provider_revision_id"),id),409,"ACTIVE_PROVIDER","请先切换活动配置再停用");
  db.update("UPDATE ai_provider_revision SET enabled=? WHERE id=?",enabled,id);
  Management.audit(db,actor,"AI_PROVIDER_STATUS",id,Map.of("enabled",enabled));return view();
 });}
 /** Explicit version is mandatory for subsequent model calls; historical revisions remain readable. */
 public Map<String,Object> route(Long version){
  if(version==null)version=((Number)Management.one(db,"SELECT version FROM ai_route_state WHERE id=1").get("version")).longValue();
  var route=Management.one(db,"SELECT h.version AS route_version,p.* FROM ai_route_history h JOIN ai_provider_revision p ON p.id=h.provider_revision_id WHERE h.version=?",version);
  return Data.row(route);
 }
 private void verifyHostScope(Map<String,Object> provider){
  if(provider.get("paid_scope")!=null)return;
  String host=URI.create(Data.str(provider,"base_url")).getHost().toLowerCase(Locale.ROOT).replaceFirst("[.]$","");
  Problem.require(db.queryForObject("SELECT COUNT(*) FROM ai_paid_budget WHERE supplier_host=?",Integer.class,host)==0,409,"PAID_SCOPE_REQUIRED","此供应商已有付费授权，请使用绑定额度的配置版本");
 }
 private void requirePaidCapacity(Object scope,long maximumMicroyuan){var paid=Management.one(db,"SELECT * FROM ai_paid_budget WHERE scope=? FOR UPDATE",scope);long used=db.queryForObject("SELECT COALESCE(SUM(CASE WHEN status='SETTLED' THEN charged_microyuan ELSE reserved_microyuan END),0) FROM ai_call_reservation WHERE paid_scope=?",Long.class,scope);Problem.require(Boolean.TRUE.equals(paid.get("approved"))&&maximumMicroyuan<=((Number)paid.get("limit_microyuan")).longValue()-used,429,"AI_PAID_BUDGET_EXHAUSTED","供应商独立额度不足或未授权");}
 public void reserve(String requestId,long routeVersion,long maximumMicroyuan){
  Problem.require(maximumMicroyuan>0,422,"INVALID_AMOUNT","额度预留必须为正数");
  tx.executeWithoutResult(s->{
   var budget=Management.one(db,"SELECT * FROM ai_budget WHERE id=1 FOR UPDATE");
   var providers=routeVersion<0?db.queryForList("SELECT base_url,paid_scope FROM ai_provider_revision WHERE id=?",-routeVersion):db.queryForList("SELECT p.base_url,p.paid_scope FROM ai_route_history h JOIN ai_provider_revision p ON p.id=h.provider_revision_id WHERE h.version=?",routeVersion);
   if(!providers.isEmpty())verifyHostScope(providers.getFirst());
   Object scope=providers.isEmpty()?null:providers.getFirst().get("paid_scope");
   Problem.require(!Boolean.TRUE.equals(budget.get("paused"))&&(scope!=null||Boolean.TRUE.equals(budget.get("free_access_confirmed"))),429,"AI_BUDGET_PAUSED","模型调用已暂停或免费资格待确认");
   var prior=db.queryForList("SELECT * FROM ai_call_reservation WHERE request_id=?",requestId);
   if(!prior.isEmpty()){var p=prior.getFirst();Problem.require(((Number)p.get("route_version")).longValue()==routeVersion&&((Number)p.get("reserved_microyuan")).longValue()==maximumMicroyuan,409,"IDEMPOTENCY_CONFLICT","调用预留参数冲突");return;}
   if(scope!=null)requirePaidCapacity(scope,maximumMicroyuan);
   long available=((Number)budget.get("limit_microyuan")).longValue()-((Number)budget.get("spent_microyuan")).longValue()-((Number)budget.get("reserved_microyuan")).longValue();
   Problem.require(maximumMicroyuan<=available,429,"AI_BUDGET_EXHAUSTED","模型等值用量预算不足");
   db.update("INSERT INTO ai_call_reservation(request_id,route_version,reserved_microyuan,status) VALUES(?,?,?,'RESERVED')",requestId,routeVersion,maximumMicroyuan);
   db.update("UPDATE ai_call_reservation SET paid_scope=? WHERE request_id=?",scope,requestId);
   db.update("UPDATE ai_budget SET reserved_microyuan=reserved_microyuan+? WHERE id=1",maximumMicroyuan);
  });
 }
 public void admit(String id,long version,long maximum,String account,long tokens){admit(id,version,maximum,account,tokens,null,null);}
 public void admit(String id,long version,long maximum,String account,long tokens,String messageId,String purpose){tx.executeWithoutResult(s->{
  Problem.require(account!=null&&account.matches("[a-f0-9]{64}")&&tokens>0,422,"INVALID_ADMISSION","模型准入参数无效");
  Management.one(db,"SELECT id FROM ai_budget WHERE id=1 FOR UPDATE");
  var old=db.queryForList("SELECT * FROM ai_call_reservation WHERE request_id=?",id);
  if(!old.isEmpty()){Problem.require(account.equals(old.getFirst().get("account_key"))&&((Number)old.getFirst().get("token_reservation")).longValue()==tokens,409,"IDEMPOTENCY_CONFLICT","准入参数冲突");reserve(id,version,maximum);return;}
  if(db.queryForObject("SELECT COUNT(*) FROM ai_provider_admission WHERE account_key=?",Integer.class,account)==0)db.update("INSERT INTO ai_provider_admission(account_key) VALUES(?)",account);
  var policy=Management.one(db,"SELECT * FROM ai_provider_admission WHERE account_key=? FOR UPDATE",account);
  var now=Data.instant(db.queryForObject("SELECT CURRENT_TIMESTAMP",java.sql.Timestamp.class));
  Problem.require(policy.get("cooldown_until")==null||!Data.instant(policy.get("cooldown_until")).isAfter(now),429,"AI_PROVIDER_COOLDOWN","供应商冷却中");
  long active=db.queryForObject("SELECT COUNT(*) FROM ai_call_reservation WHERE account_key=? AND lease_until>?",Long.class,account,Data.ts(now));
  Problem.require(active<((Number)policy.get("concurrent_limit")).longValue(),429,"AI_PROVIDER_CONCURRENCY","供应商并发已满");
  var recent=Management.one(db,"SELECT COUNT(*) AS calls,COALESCE(SUM(token_reservation),0) AS tokens FROM ai_call_reservation WHERE account_key=? AND created_at>?",account,Data.ts(now.minusSeconds(60)));
  Problem.require(((Number)recent.get("calls")).longValue()<((Number)policy.get("rpm_limit")).longValue()&&((Number)recent.get("tokens")).longValue()+tokens<=((Number)policy.get("tpm_limit")).longValue(),429,"AI_PROVIDER_RATE_LIMIT","供应商调用速率已满");
  reserve(id,version,maximum);
  db.update("UPDATE ai_call_reservation SET message_id=?,call_purpose=?,account_key=?,lease_until=?,token_reservation=?,created_at=? WHERE request_id=?",messageId,purpose,account,Data.ts(now.plusSeconds(30)),tokens,Data.ts(now),id);
 });}
 public void releaseAdmission(String id){db.update("UPDATE ai_call_reservation SET lease_until=NULL WHERE request_id=?",id);}
 public void sharedCooldown(String account,java.time.Instant until){if(until==null)return;tx.executeWithoutResult(s->{Management.one(db,"SELECT id FROM ai_budget WHERE id=1 FOR UPDATE");var row=Management.one(db,"SELECT * FROM ai_provider_admission WHERE account_key=? FOR UPDATE",account);if(row.get("cooldown_until")==null||Data.instant(row.get("cooldown_until")).isBefore(until))db.update("UPDATE ai_provider_admission SET cooldown_until=? WHERE account_key=?",Data.ts(until),account);});}
 private Map<String,Object> lockCall(String id){
  Management.one(db,"SELECT id FROM ai_budget WHERE id=1 FOR UPDATE");
  return Management.one(db,"SELECT * FROM ai_call_reservation WHERE request_id=? FOR UPDATE",id);
 }
 public void settle(String id,long charge){
  tx.executeWithoutResult(s->applySettlement(id,lockCall(id),charge));
 }
 private void applySettlement(String id,Map<String,Object> call,long charge){
  if("SETTLED".equals(call.get("status"))){
   Problem.require(Data.number(call,"charged_microyuan")==charge,409,"USAGE_CONFLICT","用量结果不一致");return;
  }
  Problem.require(charge>=0,422,"INVALID_AMOUNT","用量金额无效");
  db.update("UPDATE ai_budget SET reserved_microyuan=reserved_microyuan-?,spent_microyuan=spent_microyuan+? WHERE id=1",call.get("reserved_microyuan"),charge);
  db.update("UPDATE ai_call_reservation SET status='SETTLED',charged_microyuan=? WHERE request_id=?",charge,id);
 }
 public void settleUsage(String id,long input,long output,String priceVersion,long charge){
  tx.executeWithoutResult(s->{
   var call=lockCall(id);
   if("SETTLED".equals(call.get("status"))){
    Problem.require(Objects.equals(call.get("input_tokens"),input)&&Objects.equals(call.get("output_tokens"),output)
      &&Objects.equals(call.get("price_version"),priceVersion),409,"USAGE_CONFLICT","用量结果不一致");
   }else{
    db.update("UPDATE ai_call_reservation SET input_tokens=?,output_tokens=?,price_version=? WHERE request_id=?",input,output,priceVersion,id);
   }
   applySettlement(id,call,charge);
  });
 }
 public void callFailure(String id,String code){tx.executeWithoutResult(s->{db.update("UPDATE ai_call_reservation SET detail=? WHERE request_id=? AND status='RESERVED'",code,id);if(code.equals("AI_PAYMENT_REQUIRED"))db.update("UPDATE ai_budget SET paused=TRUE,free_access_confirmed=FALSE,version=version+1 WHERE id=1");});}
 @GetMapping("/api/v1/admin/ai-call-logs") public Map<String,Object> logs(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="")String status,HttpServletRequest request){Actor.of(request).permission("AI_LOG_READ");Problem.require(status.isBlank()||Set.of("RESERVED","SETTLED").contains(status),422,"INVALID_STATUS","状态无效");return status.isBlank()?Management.page(db,"*","FROM ai_call_reservation","created_at DESC,request_id",page,size):Management.page(db,"*","FROM ai_call_reservation WHERE status=?","created_at DESC,request_id",page,size,status);}
 @PostMapping("/api/v1/admin/ai-gateway/budget") public Map<String,Object> budget(@RequestBody Map<String,Object> input,HttpServletRequest request){var actor=admin(request);boolean paused=Management.enabled(Map.of("enabled",input.get("paused")));return tx.execute(s->{var row=Management.one(db,"SELECT * FROM ai_budget WHERE id=1 FOR UPDATE");Management.version(input,row);boolean free=Boolean.TRUE.equals(input.get("freeAccessConfirmed"));db.update("UPDATE ai_budget SET paused=?,free_access_confirmed=?,version=version+1 WHERE id=1",paused,free);Management.audit(db,actor,"AI_BUDGET_CONTROL",1,Map.of("paused",paused,"freeAccessConfirmed",free));return view();});}
 @PostMapping("/api/v1/admin/ai-gateway/calls/{id}/reconcile") public Map<String,Object> reconcile(@PathVariable String id,@RequestBody Map<String,Object> input,HttpServletRequest request){var actor=admin(request);String evidence=Management.text(input,"evidence",100);long charge=Data.number(input,"equivalentMicroyuan"),actual=Data.number(input,"actualChargeMicroyuan");Problem.require(charge>=0&&actual>=0&&charge<=1000000000&&actual<=1000000000,422,"INVALID_AMOUNT","核对金额无效");return tx.execute(s->{var call=lockCall(id);
  if("SETTLED".equals(call.get("status"))){
   Problem.require(Objects.equals(call.get("detail"),"RECONCILED:"+Data.hash(evidence))
    &&Data.number(call,"charged_microyuan")==charge&&Data.number(call,"actual_charge_microyuan")==actual,409,"ALREADY_RECONCILED","核对记录不一致");
   return view();
  }
  applySettlement(id,call,charge);db.update("UPDATE ai_call_reservation SET actual_charge_microyuan=?,detail=? WHERE request_id=?",actual,"RECONCILED:"+Data.hash(evidence),id);if(actual>0)db.update("UPDATE ai_budget SET paused=TRUE,free_access_confirmed=FALSE,version=version+1 WHERE id=1");Management.audit(db,actor,"AI_USAGE_RECONCILE",1,Map.of("requestId",id,"equivalentMicroyuan",charge,"actualChargeMicroyuan",actual,"evidenceHash",Data.hash(evidence)));return view();});}
 @PutMapping("/api/v1/admin/ai-gateway/access/{id}") public Map<String,Object> access(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest request){var actor=Actor.of(request);actor.permission("ACCOUNT_AUTHORIZE");Problem.require(input.get("permissions") instanceof List<?>,422,"INVALID_ARGUMENT","权限须为数组");var selected=new HashSet<>((List<?>)input.get("permissions"));Problem.require(AccountPermissions.AI.containsAll(selected),422,"INVALID_ARGUMENT","仅允许AI权限");return tx.execute(s->{Management.one(db,"SELECT id FROM authorization_guard WHERE id=1 FOR UPDATE");var user=Management.one(db,"SELECT * FROM app_user WHERE id=? FOR UPDATE",id);Management.version(input,user);Problem.require(db.queryForObject("SELECT COUNT(*) FROM user_role WHERE user_id=? AND role_code='PLATFORM'",Integer.class,id)==1,422,"INVALID_ROLE","仅平台管理员可授予AI权限");for(String permission:AccountPermissions.AI){db.update("DELETE FROM account_permission WHERE user_id=? AND permission_code=?",id,permission);db.update("INSERT INTO account_permission VALUES(?,?,?)",id,permission,selected.contains(permission));}db.update("UPDATE app_user SET version=version+1 WHERE id=?",id);db.update("UPDATE login_session SET revoked=TRUE WHERE user_id=?",id);Management.audit(db,actor,"AI_PERMISSIONS",id,Map.of("permissions",selected));return Map.of("id",Long.toString(id),"permissions",selected);});}
}
