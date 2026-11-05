package cn.community.trade;

import cn.community.foundation.*;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/** Completed-order partial refunds; aggregate locks serialize approvals and allocations. */
@RestController @Profile("business")
public class RefundApplications {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final BusinessClock clock;
    public RefundApplications(JdbcTemplate db,PlatformTransactionManager manager){
        this(db,manager,new BusinessClock("",false));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public RefundApplications(JdbcTemplate db,PlatformTransactionManager manager,BusinessClock clock){
        this.clock=clock;
        this.db=db;tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    private Map<String,Object> order(long id){return Management.one(db,"SELECT * FROM trade_order WHERE reservation_id=? FOR UPDATE",id);}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    @PostMapping("/api/v1/refund-applications")
    public RefundContracts.ApplicationView applyHttp(@RequestBody RefundContracts.ApplicationRequest input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request){return RefundContracts.ApplicationView.from(apply(input.values(),key,request));}
    public Map<String,Object> apply(Map<String,Object> input,String key,HttpServletRequest request){
        Actor actor=Actor.of(request);actor.role("DRIVER");Requests.key(key);
        long reservation=Data.number(input,"reservationId"),amount=Data.number(input,"amountFen");
        String reason=Management.text(input,"reason",500),evidence=Data.str(input,"evidence").strip();
        Problem.require(evidence.length()<=2000,422,"INVALID_ARGUMENT","证据说明最多2000字");
        Long previous=input.get("previousApplicationId")==null?null:Data.number(input,"previousApplicationId");
        String facts=Data.hash(Data.json(List.of(amount,reason,evidence)));
        String hash=Data.hash(Data.json(Arrays.asList(reservation,amount,reason,evidence,previous)));
        Problem.require(amount>0,422,"INVALID_ARGUMENT","退款金额必须大于零");
        return tx.execute(s->{
            var bill=order(reservation);
            Problem.require(number(bill,"user_id")==actor.id(),404,"NOT_FOUND","订单不存在");
            var existing=Requests.existing(db,actor.id(),"REFUND_APPLY",key,hash);if(existing!=null)return existing;
            Problem.require("COMPLETED".equals(bill.get("status")),409,"ORDER_NOT_COMPLETED","当前申请入口仅适用于已完成订单");
            var same=db.queryForList("SELECT * FROM refund_application WHERE reservation_id=? AND facts_hash=?",reservation,facts);
            if(!same.isEmpty()){
                var result=Data.row(same.getFirst());Requests.save(db,actor.id(),"REFUND_APPLY",key,hash,result);return result;
            }
            Problem.require(bill.get("completed_at")!=null,409,"COMPLETION_TIME_MISSING","订单完成时间尚待历史核验");
            Problem.require(clock.now().isBefore(Data.instant(bill.get("completed_at")).plus(java.time.Duration.ofDays(7))),409,"REFUND_DEADLINE","已超过完成后7天的申请期限");
            var rejected=db.queryForList("SELECT * FROM refund_application WHERE reservation_id=? AND status='REJECTED' ORDER BY id DESC LIMIT 1",reservation);
            if(previous!=null||!rejected.isEmpty()){
                Problem.require(previous!=null,409,"NEW_EVIDENCE_REQUIRED","驳回后须关联原申请并补充新证据");
                var prior=Management.one(db,"SELECT * FROM refund_application WHERE id=?",previous);
                Problem.require(number(prior,"reservation_id")==reservation&&number(prior,"user_id")==actor.id()&&"REJECTED".equals(prior.get("status")),409,"INVALID_PREVIOUS_APPLICATION","只能关联本订单已驳回的本人申请");
                Problem.require(!evidence.isBlank()&&!evidence.equals(prior.get("evidence_text")),409,"NEW_EVIDENCE_REQUIRED","须补充不同于原申请的新证据");
            }
            Problem.require(amount<=number(bill,"paid_prepay_fen")+number(bill,"paid_overtime_fen")-number(bill,"refund_fen"),409,"REFUND_AMOUNT_CONFLICT","金额超过剩余实收");
            Problem.require(db.queryForObject("SELECT COUNT(*) FROM refund_application WHERE reservation_id=? AND status IN ('PENDING','APPROVED')",Integer.class,reservation)==0,409,"REFUND_ACTIVE","已有未完成申请");
            long id=Data.id(db);
            db.update("INSERT INTO refund_application(id,reservation_id,user_id,amount_fen,reason,status,evidence_text,facts_hash,previous_application_id) VALUES(?,?,?,?,?,'PENDING',?,?,?)",id,reservation,actor.id(),amount,reason,evidence,facts,previous);
            var result=Data.row(Management.one(db,"SELECT * FROM refund_application WHERE id=?",id));
            Requests.save(db,actor.id(),"REFUND_APPLY",key,hash,result);return result;
        });
    }
    @GetMapping("/api/v1/refund-applications")
    public RefundContracts.ApplicationPage listHttp(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest request){return RefundContracts.ApplicationPage.from(list(page,size,request));}
    public Map<String,Object> list(int page,int size,HttpServletRequest request){
        Actor actor=Actor.of(request);
        if(actor.roles().contains("PLATFORM")){
            actor.permission("REFUND_READ");return Management.page(db,"*","FROM refund_application","id DESC",page,size);
        }
        actor.role("DRIVER");return Management.page(db,"*","FROM refund_application WHERE user_id=?","id DESC",page,size,actor.id());
    }
    @PostMapping("/api/v1/refund-applications/{id}/decision")
    public RefundContracts.ApplicationView decideHttp(@PathVariable long id,@RequestBody RefundContracts.DecisionRequest input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request){
        Requests.key(key);return RefundContracts.ApplicationView.from(decide(id,input.values(),key,request));
    }
    public Map<String,Object> decide(long id,Map<String,Object> input,HttpServletRequest request){return decide(id,input,null,request);}
    public Map<String,Object> decide(long id,Map<String,Object> input,String key,HttpServletRequest request){
        Actor actor=Actor.of(request);actor.permission("REFUND_APPROVE");
        String decision=Data.str(input,"decision"),note=Management.text(input,"note",500);
        Problem.require(Set.of("APPROVE","REJECT").contains(decision),422,"INVALID_ARGUMENT","审批结果无效");
        return tx.execute(s->{
            var initial=Management.one(db,"SELECT * FROM refund_application WHERE id=?",id);
            var bill=order(number(initial,"reservation_id"));
            String hash=Data.hash(Data.json(List.of(id,decision,note)));
            if(key!=null){var existing=Requests.existing(db,actor.id(),"REFUND_DECISION",key,hash);if(existing!=null)return existing;}
            var application=Management.one(db,"SELECT * FROM refund_application WHERE id=? FOR UPDATE",id);
            Problem.require("PENDING".equals(application.get("status")),409,"APPLICATION_DECIDED","申请已审批，请刷新");
            if(decision.equals("APPROVE")){
                long reservation=number(application,"reservation_id"),amount=number(application,"amount_fen");
                RefundLedger.mapHistory(db,reservation);
                var receipts=new ArrayList<RefundAllocation.Receipt>();
                for(var receipt:db.queryForList("SELECT id,amount_fen FROM payment_attempt WHERE reservation_id=? AND kind IN ('PREPAY','OVERTIME') AND status='SUCCESS' ORDER BY id FOR UPDATE",reservation)){
                    long receiptId=number(receipt,"id");
                    long used=db.queryForObject("SELECT COALESCE(SUM(a.amount_fen),0) FROM refund_receipt_allocation a JOIN payment_attempt p ON p.id=a.attempt_id WHERE a.receipt_id=? AND p.status<>'FAILED'",Long.class,receiptId);
                    receipts.add(new RefundAllocation.Receipt(receiptId,new RefundAllocation.Balance(number(receipt,"amount_fen"),used,0)));
                }
                long reserved=db.queryForObject("SELECT COALESCE(SUM(amount_fen),0) FROM payment_attempt WHERE reservation_id=? AND kind='REFUND' AND status IN ('CREATED','SUBMITTED','UNKNOWN','REVIEW')",Long.class,reservation);
                RefundAllocation.Plan plan;
                try{plan=RefundAllocation.allocate(new RefundAllocation.Balance(number(bill,"paid_prepay_fen")+number(bill,"paid_overtime_fen"),number(bill,"refund_fen"),reserved),new RefundAllocation.Balance(amount,0,0),receipts,amount);}
                catch(IllegalArgumentException e){throw new Problem(409,"REFUND_AMOUNT_CONFLICT","可退额度已变化，请重新核查");}
                for(var item:plan.items()){
                    long attempt=Data.id(db);
                    db.update("INSERT INTO payment_attempt(id,reservation_id,user_id,kind,amount_fen,status,channel_no,application_id,receipt_attempt_id) VALUES(?,?,?,'REFUND',?,'CREATED',?,?,?)",attempt,reservation,application.get("user_id"),item.amount(),"REFUND-APP-"+attempt,id,item.receiptId());
                    db.update("INSERT INTO refund_receipt_allocation VALUES(?,?,?)",attempt,item.receiptId(),item.amount());
                }
                db.update("UPDATE trade_order SET bill_version=bill_version+1 WHERE reservation_id=?",reservation);
            }
            db.update("UPDATE refund_application SET status=?,decision_by=?,decision_note=? WHERE id=?",decision.equals("APPROVE")?"APPROVED":"REJECTED",actor.id(),note,id);
            db.update("INSERT INTO audit_log(actor_id,action,resource_id,reason) VALUES(?,'REFUND_DECISION',?,?)",actor.id(),Long.toString(id),decision+":"+note);
            var result=Data.row(Management.one(db,"SELECT * FROM refund_application WHERE id=?",id));
            if(key!=null)Requests.save(db,actor.id(),"REFUND_DECISION",key,hash,result);
            return result;
        });
    }
}
