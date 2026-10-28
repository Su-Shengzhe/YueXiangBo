import {readFile,writeFile,mkdir,readdir,copyFile} from 'node:fs/promises'
import {createHash} from 'node:crypto'
import {selectMigrationReport} from './evidence-reports.mjs'
const evidenceDate=process.argv[2]||'2026-10-25'
if(!/^\d{4}-\d{2}-\d{2}$/.test(evidenceDate))throw Error('Expected evidence date YYYY-MM-DD')
const out=`docs/evidence/${evidenceDate}-week56`
const read=async p=>JSON.parse(await readFile(p,'utf8'))
const save=async (name,data)=>writeFile(`${out}/${name}.json`,JSON.stringify(data,null,2)+'\n')
const latest=async p=>(await readdir(p,{withFileTypes:true})).filter(x=>x.isDirectory()).map(x=>x.name).sort().at(-1)
const build=await read('.runtime/backend-build.json')
if(build.status!=='PASS')throw Error('Backend build not verified')
const sources=[...build.source.files]
async function collect(dir){for(const e of await readdir(dir,{withFileTypes:true})){const p=dir+'/'+e.name;if(e.isDirectory())await collect(p);else sources.push({path:p,sha256:createHash('sha256').update(await readFile(p)).digest('hex')})}}
for(const dir of ['frontend/src','frontend/business-tests','frontend/regression-tests','scripts','docs/contracts'])await collect(dir)
for(const p of ['frontend/package.json','frontend/package-lock.json','frontend/playwright.business.config.ts','frontend/playwright.regression.config.ts'])sources.push({path:p,sha256:createHash('sha256').update(await readFile(p)).digest('hex')})
for(const f of build.source.files)if(createHash('sha256').update(await readFile(f.path)).digest('hex')!==f.sha256)throw Error('Backend source drift: '+f.path)
const migrations=await selectMigrationReport({reportPath:process.argv[3],fingerprint:build.source.fingerprint})
await mkdir(out,{recursive:true})
sources.sort((a,b)=>a.path.localeCompare(b.path))
const manifest={createdAt:new Date().toISOString(),backendFingerprint:build.source.fingerprint,candidateFingerprint:createHash('sha256').update(JSON.stringify(sources)).digest('hex'),files:sources}
await save('source-manifest',manifest)
await save('backend-build',{id:build.id,status:build.status,startedAt:build.startedAt,endedAt:build.endedAt,sourceFingerprint:build.source.fingerprint,artifacts:build.artifacts})
async function report(name,path){const data=await read(path);if(data.status==='FAIL'||(data.results||data.checks||[]).some(x=>x.status==='FAIL'))throw Error('Failed report: '+path);await save(name,{sourceReport:path,backendFingerprint:build.source.fingerprint,...data})}
const browserDir='.runtime/business/browser/'+await latest('.runtime/business/browser')
async function browser(name,path){const data=await read(path);const tests=[];function visit(s){for(const spec of s.specs||[])for(const test of spec.tests||[])tests.push({title:spec.title,file:spec.file,status:test.status,results:test.results.map(r=>({status:r.status,duration:r.duration}))});for(const c of s.suites||[])visit(c)}visit(data);if(tests.some(t=>t.status!=='expected'))throw Error('Browser failures: '+path);await save(name,{sourceReport:path,stats:data.stats,tests})}
await browser('business-browser',browserDir+'/browser-results.json')
await browser('frontend-regression','.runtime/optimization-browser.json')
await report('resource-api','.runtime/business/week56/'+await latest('.runtime/business/week56')+'/api-results.json')
await report('m1-api','.runtime/business/checks/'+await latest('.runtime/business/checks')+'/api-results.json')
await report('reliability','.runtime/optimization-reliability.json')
await report('recovery','.runtime/business/recovery/'+await latest('.runtime/business/recovery')+'/result.json')
await save('migrations',{sourceReport:migrations.path,...migrations.data})
await report('contract','.runtime/business/contracts/contract-check.json')
if(evidenceDate>='2026-10-26')await report('admin-concurrency','.runtime/business/admin-concurrency/'+await latest('.runtime/business/admin-concurrency')+'/results.json')
for(const file of ['week56-platform.png','week56-windows.png','week56-ten-yuan-bill.png'])await copyFile(browserDir+'/'+file,out+'/'+file)
console.log(JSON.stringify({evidence:out,candidateFingerprint:manifest.candidateFingerprint}))
