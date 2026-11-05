package cn.community.trade;

import java.util.*;

/** Pure allocation rules; callers must lock and persist all three levels atomically. */
final class RefundAllocation {
    record Balance(long total, long succeeded, long reserved) {
        Balance {
            if (total < 0 || succeeded < 0 || reserved < 0 || succeeded > total || reserved > total - succeeded)
                throw new IllegalArgumentException("Invalid refund balance");
        }
        long available() { return total - succeeded - reserved; }
        Balance reserve(long amount) {
            if (amount <= 0 || amount > available()) throw new IllegalArgumentException("Refund quota exceeded");
            return new Balance(total, succeeded, reserved + amount);
        }
        Balance finish(long amount, boolean success) {
            if (amount <= 0 || amount > reserved) throw new IllegalArgumentException("Missing refund reservation");
            return new Balance(total, success ? Math.addExact(succeeded, amount) : succeeded, reserved - amount);
        }
    }
    record Receipt(long id, Balance balance) {}
    record Item(long receiptId, long amount) {}
    record Plan(Balance order, Balance parent, List<Item> items) {
        Plan { items = List.copyOf(items); }
    }

    static Plan allocate(Balance order, Balance parent, List<Receipt> receipts, long requested) {
        // Validate the parent target independently of the larger order balance.
        var nextOrder = order.reserve(requested);
        var nextParent = parent.reserve(requested);
        var sorted = new ArrayList<>(receipts);
        sorted.sort(Comparator.comparingLong(Receipt::id));
        var ids = new HashSet<Long>();
        for (var receipt : sorted)
            if (receipt.id() <= 0 || receipt.balance() == null || !ids.add(receipt.id()))
                throw new IllegalArgumentException("Invalid or duplicate receipt");
        var items = new ArrayList<Item>();
        long remaining = requested;
        for (var receipt : sorted) {
            long amount = Math.min(remaining, receipt.balance().available());
            if (amount > 0) { items.add(new Item(receipt.id(), amount)); remaining -= amount; }
            if (remaining == 0) break;
        }
        if (remaining != 0) throw new IllegalArgumentException("Receipt quota exceeded");
        return new Plan(nextOrder, nextParent, items);
    }
}
