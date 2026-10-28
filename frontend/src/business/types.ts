export interface User { id: string; username: string; roles: string[]; communities?: string[]; permissions?: string[] }
export interface Community { id: string; name: string }
export interface Vehicle { id: string; plate: string }
export interface Space { id: string; name: string; hourlyFen: number; estimatedFen: number; windowId?: string; windowVersion?: number }
export interface Reservation {
 reservationId: string; plate: string; startAt: string; endAt: string;
 status: string; prepayFen: number; payDeadline: string;
}
export interface PaymentAttempt { id: string; kind: string; amountFen: number; status: string }
export interface Bill {
 reservationId: string; status: string; prepayFen: number; overtimeFen: number;
 paidPrepayFen: number; paidOvertimeFen: number; refundFen: number;
 billVersion: number; reservationVersion: number; attempts: PaymentAttempt[];
}
export interface SearchCriteria { communityId: string; startAt: string; endAt: string }
