export interface User { id: string; username: string; roles: string[]; communities?: string[]; permissions?: string[] }
export interface Community { id: string; name: string }
export interface Vehicle { id: string; plate: string }
export interface Space { id: string; name: string; hourlyFen: number; estimatedFen: number; windowId?: string; windowVersion?: number }
export interface Reservation {
 reservationId: string; plate: string; startAt: string; endAt: string;
 status: string; prepayFen: number; payDeadline: string; closeReason?: string; entryBlockedAt?: string;
}
export interface PaymentAttempt { id: string; kind: string; amountFen: number; status: string }
export interface Bill {
 reservationId: string; status: string; prepayFen: number; overtimeFen: number;
 paidPrepayFen: number; paidOvertimeFen: number; refundFen: number;
 billVersion: number; reservationVersion: number; attempts: PaymentAttempt[];
}
export interface SearchCriteria { communityId: string; startAt: string; endAt: string }
export type Role = 'DRIVER' | 'OWNER' | 'PROPERTY' | 'PLATFORM'
export type ReviewStatus = 'DRAFT' | 'PENDING' | 'APPROVED' | 'REJECTED'
export interface PageResult<T> { items: T[]; total: number; page: number; size: number }
export interface ManagedUser { id: string; username: string; enabled: boolean; version: number; roles: Role[]; communities: string[] }
export interface Authorization { communityId: string; version: number; userIds: string[]; users: Pick<ManagedUser, 'id' | 'username' | 'enabled'>[] }
export interface ManagedVehicle { id: string; plate: string; enabled: boolean; version: number }
export interface ManagedSpace { id: string; communityId: string; spaceCode: string; locationDesc: string; spaceType: string; proofText: string; reviewStatus: ReviewStatus; reviewComment: string; enabled: boolean; version: number }
export interface ShareWindow { id: string; spaceId: string; startAt: string; endAt: string; hourlyFen: number; enabled: boolean; version: number }
export interface AuditEntry { id: string; actorId: string; action: string; resourceId: string; createdAt: string; detailJson: string | null }
/** Common columns for the six existing management views. Forms use an explicit subset. */
export interface ManagementRow {
 id: string; version: number; enabled?: boolean; plate?: string; name?: string; username?: string;
 communityId?: string; address?: string; spaceCode?: string; locationDesc?: string; spaceType?: string;
 proofText?: string; reviewStatus?: ReviewStatus; reviewComment?: string; roles?: Role[]; communities?: string[];
 action?: string; actorId?: string; resourceId?: string; createdAt?: string; detailJson?: string;
}
