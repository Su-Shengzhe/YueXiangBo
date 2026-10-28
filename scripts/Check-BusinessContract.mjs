import assert from 'node:assert/strict'
import { readFile, readdir, mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
const spec=JSON.parse(await readFile('docs/contracts/openapi-m1.json','utf8')),operations=[],ids=new Set(),references=[]
const normalize=p=>p.replace(/\{[^}]+\}/g,'{}')
function walk(value){if(!value||typeof value!=='object')return;if(value.$ref)references.push(value.$ref);Object.values(value).forEach(walk)}
walk(spec)
for(const ref of references){assert.ok(ref.startsWith('#/'));let current=spec;for(const part of ref.slice(2).split('/'))current=current?.[part];assert.ok(current,'Missing reference '+ref)}
for(const [route,item]of Object.entries(spec.paths))for(const [method,op]of Object.entries(item)){
 if(!['get','post','put','patch','delete'].includes(method))continue
 assert.ok(!ids.has(op.operationId),'Duplicate operationId');ids.add(op.operationId)
 for(const m of route.matchAll(/\{(\w+)\}/g))assert.ok(op.parameters?.some(p=>p.in==='path'&&p.name===m[1]&&p.required),'Missing path parameter '+route)
 assert.ok(op.responses?.['200']||op.responses?.['202']);assert.ok(Array.isArray(op.security));operations.push(method+' '+normalize(route))
}
const handlers=[]
async function scan(dir){for(const entry of await readdir(dir,{withFileTypes:true})){if(entry.name==='target')continue;const p=path.join(dir,entry.name);if(entry.isDirectory())await scan(p);else if(entry.name.endsWith('.java')&&p.includes(path.join('src','main'))){const source=await readFile(p,'utf8');for(const m of source.matchAll(/@(Get|Post|Put|Patch|Delete)Mapping\("(\/api\/v1\/[^\"]+)"\)/g)){const routes=m[2].includes('{action}')?[m[2].replace('{action}','entry'),m[2].replace('{action}','exit')]:[m[2]];for(const route of routes)handlers.push(m[1].toLowerCase()+' '+normalize(route.replace('/api/v1','')))}}}}
await scan('backend')
for(const handler of handlers)assert.ok(operations.includes(handler),'Undocumented handler '+handler)
for(const op of operations)assert.ok(handlers.includes(op),'No matching handler '+op)
const result={createdAt:new Date().toISOString(),status:'PASS',paths:Object.keys(spec.paths).length,operations:operations.length,handlers:handlers.length,localReferences:references.length,checks:['handler coverage','path parameters','operation IDs','local references','security declarations'],limits:'Structural checks; not a full OpenAPI metaschema validator or exhaustive runtime response validation.'}
await mkdir('.runtime/business/contracts',{recursive:true});await writeFile('.runtime/business/contracts/contract-check.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result))
