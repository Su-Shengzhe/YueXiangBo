package cn.community.foundation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MoneyTest {
 @Test void fractionalDurationsRoundUpWithoutTruncation(){
  assertEquals(0,Data.fee(500,java.time.Duration.ZERO));
  assertEquals(250,Data.fee(500,java.time.Duration.ofNanos(1)));
  assertEquals(250,Data.fee(500,java.time.Duration.ofMillis(500)));
  assertEquals(250,Data.fee(500,java.time.Duration.ofSeconds(1800)));
  assertEquals(500,Data.fee(500,java.time.Duration.ofSeconds(1800).plusNanos(1)));
  assertEquals(500,Data.fee(500,java.time.Duration.ofMillis(1800500)));
  assertThrows(Problem.class,()->Data.fee(500,java.time.Duration.ofNanos(-1)));
 }
 @Test void thirtyMinuteBoundaries(){assertEquals(0,Data.fee(500,0));assertEquals(250,Data.fee(500,1));assertEquals(250,Data.fee(500,1800));assertEquals(500,Data.fee(500,1801));assertEquals(500,Data.fee(500,3600));}
 @Test void halfUpForOddFen(){assertEquals(251,Data.fee(501,1800));assertEquals(501,Data.fee(501,3600));assertEquals(752,Data.fee(501,3601));}
 @Test void invalidAmountCannotBeCharged(){assertThrows(Problem.class,()->Data.fee(-1,10));assertThrows(Problem.class,()->Data.fee(500,-1));}
 @Test void identifiersStayStrings(){assertEquals("9007199254740993",Data.row(java.util.Map.of("reservation_id",9007199254740993L)).get("reservationId"));}
}
