import {readFile,readdir} from 'node:fs/promises'
import {join} from 'node:path'

export async function selectMigrationReport({directory='.runtime/business/migrations',reportPath,fingerprint}) {
 if(!reportPath){
  const runs=(await readdir(directory,{withFileTypes:true})).filter(e=>e.isDirectory()&&/^\d{14}$/.test(e.name)).map(e=>e.name).sort()
  if(!runs.length)throw Error('No migration run found; run Test-Week56Migrations.ps1 first')
  // Never fall back to an older passing run when the newest run failed or is incomplete.
  reportPath=join(directory,runs.at(-1),'results.json')
 }
 const data=JSON.parse((await readFile(reportPath,'utf8')).replace(/^\uFEFF/,''))
 if(!fingerprint||data.sourceFingerprint!==fingerprint)throw Error('Migration source fingerprint does not match the current build')
 if(data.status==='FAIL'||!Array.isArray(data.results)||!data.results.length||data.results.some(r=>r.status!=='PASS'))throw Error('Migration report failed or is incomplete: '+reportPath)
 return {path:reportPath,data}
}
