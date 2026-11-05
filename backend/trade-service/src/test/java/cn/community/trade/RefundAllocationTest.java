package cn.community.trade;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import cn.community.trade.RefundAllocation.*;

class RefundAllocationTest {
    @Test void splitsAcrossReceiptsInLockOrderAndReservesBothAggregateLevels() {
        var plan = RefundAllocation.allocate(new Balance(10000, 1000, 1000), new Balance(2000, 0, 0),
                List.of(new Receipt(2, new Balance(4000, 0, 0)), new Receipt(1, new Balance(1500, 500, 500))), 2000);
        assertEquals(List.of(new Item(1, 500), new Item(2, 1500)), plan.items());
        assertEquals(3000, plan.order().reserved());
        assertEquals(2000, plan.parent().reserved());
    }
    @Test void parentQuotaCannotBeBypassedByLargerOrderOrReceipts() {
        assertThrows(IllegalArgumentException.class, () -> RefundAllocation.allocate(new Balance(10000,0,0),
                new Balance(2000,0,2000), List.of(new Receipt(1,new Balance(10000,0,0))), 2000));
    }
    @Test void orderAndReceiptQuotasAreIndependent() {
        assertThrows(IllegalArgumentException.class, () -> RefundAllocation.allocate(new Balance(1000,0,1000),
                new Balance(2000,0,0), List.of(new Receipt(1,new Balance(10000,0,0))), 1));
        assertThrows(IllegalArgumentException.class, () -> RefundAllocation.allocate(new Balance(10000,0,0),
                new Balance(2000,0,0), List.of(new Receipt(1,new Balance(1000,500,500))), 1));
    }
    @Test void successConvertsReservationAndFailureReleasesIt() {
        var held = new Balance(2000,0,1500);
        assertEquals(new Balance(2000,500,1000), held.finish(500,true));
        assertEquals(new Balance(2000,0,1000), held.finish(500,false));
        assertThrows(IllegalArgumentException.class, () -> held.finish(1501,true));
    }
    @Test void rejectsDuplicateReceiptsAndHandlesMaximumAmountsWithoutOverflow() {
        var balance = new Balance(Long.MAX_VALUE,0,0);
        assertThrows(IllegalArgumentException.class, () -> RefundAllocation.allocate(balance,balance,
                List.of(new Receipt(1,balance),new Receipt(1,balance)), 1));
        var plan = RefundAllocation.allocate(balance,balance,List.of(new Receipt(1,balance)),Long.MAX_VALUE);
        assertEquals(0,plan.order().available());
        assertEquals(Long.MAX_VALUE,plan.parent().finish(Long.MAX_VALUE,true).succeeded());
    }
}
