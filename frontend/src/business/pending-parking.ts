export interface PendingParking {
 account: string
 kind: 'reserve' | 'gate' | 'unavailable'
 key: string
 path: string
 body: Record<string, unknown>
}
const prefix = 'parking.pending-operation.v2.'
const legacyKey = 'parking.pending-operation.v1'
const memory = new Map<string, PendingParking>()
const volatileOnly = new Set<string>(), completedKeys = new Set<string>()
function storageKey(value: PendingParking) { return prefix + encodeURIComponent(value.account) + '.' + value.key }
function valid(value: unknown): value is PendingParking {
 if (!value || typeof value !== 'object') return false
 const p = value as PendingParking
 return typeof p.account === 'string' && /^[A-Za-z0-9_-]{8,80}$/.test(p.key) && !!p.body && typeof p.body === 'object' && !Array.isArray(p.body) &&
  ((p.kind === 'reserve' && p.path === '/reservations') ||
   (p.kind === 'gate' && /^\/property\/reservations\/\d+\/(entry|exit)$/.test(p.path)) ||
   (p.kind === 'unavailable' && /^\/property\/reservations\/\d+\/unavailable$/.test(p.path)))
}
/** Completion only removes this request; other tabs retain their own recovery entries. */
export function savePending(value: PendingParking | null, completed?: PendingParking | null): boolean {
 const operation=value ?? completed;if(!operation)return true
 const key=storageKey(operation)
 if(value){memory.set(key,value);completedKeys.delete(key)}else{memory.delete(key);volatileOnly.delete(key);completedKeys.add(key)}
 try{if(value)localStorage.setItem(key,JSON.stringify(value));else localStorage.removeItem(key);volatileOnly.delete(key);return true}
 catch{if(value)volatileOnly.add(key);return false}
}
export function readPending(account: string): PendingParking | null {
 try {
  const old=localStorage.getItem(legacyKey)
  if(old){try{const value: unknown=JSON.parse(old);if(valid(value)&&!completedKeys.has(storageKey(value))&&savePending(value))localStorage.removeItem(legacyKey)}catch{/* Isolate malformed legacy data. */}}
  const stored=new Set<string>()
  for(let i=0;i<localStorage.length;i++){
   const key=localStorage.key(i);if(!key?.startsWith(prefix)||completedKeys.has(key))continue
   try{const value: unknown=JSON.parse(localStorage.getItem(key)||'null');if(valid(value)&&storageKey(value)===key){stored.add(key);memory.set(key,value)}}catch{/* Isolate malformed records. */}
  }
  for(const key of memory.keys())if(!stored.has(key)&&!volatileOnly.has(key))memory.delete(key)
 }catch{/* Keep unpersisted operations recoverable within this page. */}
 return [...memory.values()].find(value=>value.account===account) ?? null
}
