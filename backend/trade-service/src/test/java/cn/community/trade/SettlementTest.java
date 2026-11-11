package cn.community.trade;
import cn.community.foundation.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SettlementTest {
 TradeReliabilityTest fixture;Settlements api;SettlementWorker worker;
 @BeforeEach void setup() throws Exception {fixture=new TradeReliabilityTest();fixture.setup();api=new Settlements(fixture.db,fixture.manager);worker=new SettlementWorker(fixture.db,fixture.manager);fixture.seed(1);fixture.worker.process();fixture.db.update("UPDATE trade_order SET owner_id=3,status='COMPLETED',completed_at=CURRENT_TIMESTAMP");}
 long start(){var result=api.execute(Map.of("reservationIds",List.of(1)),"batch-key",fixture.admin());return Long.parseLong(result.get("batchId").toString());}
 long item(){return fixture.db.queryForObject("SELECT id FROM settlement_item",Long.class);}
 void due(){fixture.db.update("UPDATE settlement_attempt SET next_retry_at=TIMESTAMP '2000-01-01 00:00:00',lease_until=NULL");}
 @Test void successIsIdempotentAndDuplicateBatchCannotPayAgain(){
  start();start();worker.process();worker.process();assertEquals("SETTLED",fixture.db.queryForObject("SELECT settlement_status FROM trade_order",String.class));assertEquals(1,worker.channel.submissionCount());assertEquals(450L,fixture.db.queryForObject("SELECT requested_fen FROM settlement_attempt",Long.class));
  assertThrows(Problem.class,()->api.execute(Map.of("reservationIds",List.of(1)),"another-key",fixture.admin()));
 }
 @Test void failedAttemptRecalculatesAfterRefundWithoutOverwritingOldAmount(){
  start();worker.channel.configure(true,"FAILED",0,false);worker.process();
  var refunds=new RefundApplications(fixture.db,fixture.manager);var app=refunds.apply(Map.of("reservationId",1,"amountFen",200,"reason","partial"),"refund-one",fixture.request());refunds.decide(Long.parseLong(app.get("id").toString()),Map.of("decision","APPROVE","note","verified"),fixture.admin());fixture.worker.process();
  var retry=api.retry(item(),Map.of("reason","rechecked"),"retry-one",fixture.admin());assertEquals(270L,((Number)retry.get("requestedFen")).longValue());
  assertEquals(450L,fixture.db.queryForObject("SELECT requested_fen FROM settlement_attempt ORDER BY id LIMIT 1",Long.class));
  worker.channel.configure(true,"SUCCESS",0,false);worker.process();assertEquals("SETTLED",fixture.db.queryForObject("SELECT settlement_status FROM trade_order",String.class));
 }
 @Test void unknownNeverResubmitsAndExhaustionPreservesRefundGuard(){
  start();worker.channel.configure(true,"UNKNOWN",0,false);worker.process();for(int i=0;i<8;i++){due();worker.process();}
  assertEquals(1,worker.channel.submissionCount());assertEquals(5,worker.channel.queryCount());assertEquals("REVIEW",fixture.db.queryForObject("SELECT status FROM settlement_attempt",String.class));
  assertThrows(Problem.class,()->api.retry(item(),Map.of("reason","unknown"),"retry-unknown",fixture.admin()));
  var refunds=new RefundApplications(fixture.db,fixture.manager);var app=refunds.apply(Map.of("reservationId",1,"amountFen",100,"reason","claim"),"during-settlement",fixture.request());
  assertEquals("SETTLEMENT_IN_PROGRESS",assertThrows(Problem.class,()->refunds.decide(Long.parseLong(app.get("id").toString()),Map.of("decision","APPROVE","note","verified"),fixture.admin())).code);
 }
 @Test void responseLossQueriesOriginalPayoutAndPostsOnce(){start();worker.channel.configure(true,"SUCCESS",0,true);worker.process();due();worker.process();assertEquals(1,worker.channel.submissionCount());assertEquals(1,worker.channel.queryCount());assertEquals(1,fixture.db.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE kind='SETTLEMENT'",Integer.class));}
 @Test void settledRefundAppendsReceivableWithCumulativeRounding(){
  start();worker.process();var refunds=new RefundApplications(fixture.db,fixture.manager);
  for(int i=0;i<2;i++){var app=refunds.apply(Map.of("reservationId",1,"amountFen",1,"reason","refund-"+i),"rounding-"+i,fixture.request());refunds.decide(Long.parseLong(app.get("id").toString()),Map.of("decision","APPROVE","note","verified"),fixture.admin());fixture.worker.process();}
  assertEquals(450L,fixture.db.queryForObject("SELECT requested_fen FROM settlement_item",Long.class));assertEquals(1L,fixture.db.queryForObject("SELECT SUM(owner_recovery_fen) FROM settlement_adjustment",Long.class));assertEquals(1L,fixture.db.queryForObject("SELECT SUM(platform_reversal_fen) FROM settlement_adjustment",Long.class));assertEquals(2,fixture.db.queryForObject("SELECT COUNT(*) FROM settlement_adjustment WHERE status='PENDING_RECOVERY'",Integer.class));
 }
 @Test void ledgerDifferenceAndPendingDisputeBlockExecution(){fixture.db.update("UPDATE trade_order SET refund_fen=1");assertThrows(Problem.class,this::start);fixture.db.update("UPDATE trade_order SET refund_fen=0");new RefundApplications(fixture.db,fixture.manager).apply(Map.of("reservationId",1,"amountFen",100,"reason","claim"),"pending-claim",fixture.request());assertThrows(Problem.class,this::start);assertEquals(0,worker.channel.submissionCount());}
 @Test void zeroNetCompletesWithoutChannel(){fixture.db.update("UPDATE trade_order SET refund_fen=500");fixture.db.update("INSERT INTO ledger_entry(reservation_id,channel_no,kind,amount_fen) VALUES(1,'FULL-REFUND','REFUND',500)");start();worker.process();assertEquals(0,worker.channel.submissionCount());assertEquals("ZERO_AMOUNT",fixture.db.queryForObject("SELECT last_error FROM settlement_attempt",String.class));}
}
