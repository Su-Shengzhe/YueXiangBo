import {renameSync,writeFileSync} from 'node:fs'

const groups = {query:['Q',30,27], rule:['R',30,27], bill:['B',20,18], boundary:['E',20,20]}

export function verdict(results,billRaceOnly,hash,error) {
 const expected=new Map()
 const counts={}
 for(const [category,[prefix,size]] of Object.entries(groups)){
  for(let i=1;i<=size;i++){
   const id=prefix+String(i).padStart(2,'0')
   if(!billRaceOnly || id==='B17' || id==='B18') expected.set(id,category)
  }
  counts[category]={total:billRaceOnly?(category==='bill'?2:0):size,passed:0}
 }
 const seen=new Set(), invalid=[]
 for(const row of results){
  if(!row || !expected.has(row.id) || seen.has(row.id) || expected.get(row.id)!==row.category || !['PASS','FAIL'].includes(row.status)){
   invalid.push(row?.id??null)
   continue
  }
  seen.add(row.id)
  if(row.status==='PASS') counts[row.category].passed++
 }
 const missing=[...expected.keys()].filter(id=>!seen.has(id))
 const complete=!error && !missing.length && !invalid.length
 const meetsThreshold=billRaceOnly?counts.bill.passed===2:Object.entries(groups).every(([category,[,,threshold]])=>counts[category].passed>=threshold)
 return {suite:billRaceOnly?'bill-race':'full',hash,counts,missing,invalid,complete,passed:Boolean(complete&&meetsThreshold),...(error?{error}:{})}
}

export function writeJson(path,value){
 const temp=path+'.tmp'
 writeFileSync(temp,JSON.stringify(value,null,2))
 renameSync(temp,path)
}
