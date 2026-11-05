package cn.community.trade;

import java.util.*;
import java.math.BigDecimal;
import cn.community.foundation.Problem;

/** Public request types; money is expressed in integer fen. */
public final class RefundContracts {
    private RefundContracts() {}
    public enum Decision { APPROVE, REJECT }
    public enum Status { PENDING, APPROVED, REJECTED, COMPLETED, PARTIAL_FAILED }
    public enum AttemptStatus { CREATED, SUBMITTED, UNKNOWN, REVIEW, SUCCESS, FAILED }
    public enum MappingStatus { PASS, REVIEW }
    public record RetryRequest(String reason){public Map<String,Object> values(){return Map.of("reason",reason==null?"":reason);}}
    public record AttemptView(String id,String reservationId,long amountFen,AttemptStatus status,String retryOfId,
            String channelNo,String applicationId,String receiptAttemptId,String createdAt){
        static AttemptView from(Map<String,Object> row){return new AttemptView(text(row,"id"),text(row,"reservationId"),
            ((Number)row.get("amountFen")).longValue(),AttemptStatus.valueOf(text(row,"status")),text(row,"retryOfId"),
            text(row,"channelNo"),text(row,"applicationId"),text(row,"receiptAttemptId"),text(row,"createdAt"));}
    }
    public record AttemptPage(List<AttemptView> items,long total,int page,int size){
        @SuppressWarnings("unchecked") static AttemptPage from(Map<String,Object> result){return new AttemptPage(
            ((List<Map<String,Object>>)result.get("items")).stream().map(AttemptView::from).toList(),
            ((Number)result.get("total")).longValue(),((Number)result.get("page")).intValue(),((Number)result.get("size")).intValue());}
    }
    public record MappingItem(String reservationId,MappingStatus status,String code){}
    public record MappingPage(List<MappingItem> items,String next,boolean hasMore){
        @SuppressWarnings("unchecked") static MappingPage from(Map<String,Object> result){return new MappingPage(
            ((List<Map<String,Object>>)result.get("items")).stream().map(row->new MappingItem(text(row,"reservationId"),MappingStatus.valueOf(text(row,"status")),text(row,"code"))).toList(),
            text(result,"next"),Boolean.TRUE.equals(result.get("hasMore")));}
    }
    private static String text(Map<String,Object> row,String field){return Objects.toString(row.get(field),null);}
    public record ApplicationView(String id,String reservationId,String userId,long amountFen,String reason,Status status,
            String evidenceText,String previousApplicationId,String decisionBy,String decisionNote,String createdAt) {
        static ApplicationView from(Map<String,Object> row){return new ApplicationView(text(row,"id"),text(row,"reservationId"),text(row,"userId"),
            ((Number)row.get("amountFen")).longValue(),text(row,"reason"),Status.valueOf(text(row,"status")),text(row,"evidenceText"),
            text(row,"previousApplicationId"),text(row,"decisionBy"),text(row,"decisionNote"),text(row,"createdAt"));}
        private static String text(Map<String,Object> row,String field){return Objects.toString(row.get(field),null);}
    }
    public record ApplicationPage(List<ApplicationView> items,long total,int page,int size){
        @SuppressWarnings("unchecked") static ApplicationPage from(Map<String,Object> result){return new ApplicationPage(
            ((List<Map<String,Object>>)result.get("items")).stream().map(ApplicationView::from).toList(),
            ((Number)result.get("total")).longValue(),((Number)result.get("page")).intValue(),((Number)result.get("size")).intValue());}
    }
    public record DecisionRequest(Decision decision, String note) {
        public Map<String,Object> values(){return Map.of("decision",decision==null?"":decision.name(),"note",note==null?"":note);}
    }
    public record ApplicationRequest(BigDecimal reservationId, BigDecimal amountFen, String reason, String evidence, BigDecimal previousApplicationId) {
        public Map<String,Object> values(){
            var values=new HashMap<String,Object>();values.put("reservationId",integer(reservationId));values.put("amountFen",integer(amountFen));
            values.put("reason",reason);values.put("evidence",evidence);values.put("previousApplicationId",integer(previousApplicationId));return values;
        }
        private static Long integer(BigDecimal value){
            try{return value==null?null:value.longValueExact();}
            catch(ArithmeticException invalid){throw new Problem(422,"INVALID_ARGUMENT","金额和编号必须为范围内的整数");}
        }
    }
}
