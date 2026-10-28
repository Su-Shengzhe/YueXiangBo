// Persist only an account/section marker, never request bodies or credentials.
const memory = new Set<string>()
export function pendingManagement(userId:string, section:string) {
 const key='yuexiangbo:pending-management:'+userId+':'+section
 return {
  read(){try{return memory.has(key)||localStorage.getItem(key)==='pending'}catch{return memory.has(key)}},
  set(value:boolean){if(value)memory.add(key);else memory.delete(key);try{if(value)localStorage.setItem(key,'pending');else localStorage.removeItem(key)}catch{/* In-memory protection remains available when storage is disabled. */}}
 }
}
