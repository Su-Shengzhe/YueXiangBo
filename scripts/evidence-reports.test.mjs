import {test} from 'node:test'
import assert from 'node:assert/strict'
import {mkdtemp,mkdir,writeFile,rm} from 'node:fs/promises'
import {tmpdir} from 'node:os'
import {join} from 'node:path'
import {selectMigrationReport} from './evidence-reports.mjs'

async function fixture(t){const directory=await mkdtemp(join(tmpdir(),'yxp-evidence-'));t.after(()=>rm(directory,{recursive:true,force:true}));return directory}
async function put(directory,id,data){const dir=join(directory,id);await mkdir(dir);const path=join(dir,'results.json');if(data)await writeFile(path,JSON.stringify(data));return path}
const pass={sourceFingerprint:'current',results:[{status:'PASS',name:'migration'}]}
test('selects latest run without requiring the historical fixed directory',async t=>{const directory=await fixture(t);await put(directory,'20261024000000',pass);const latest=await put(directory,'20261026000000',pass);assert.equal((await selectMigrationReport({directory,fingerprint:'current'})).path,latest)})
test('latest failure never falls back to older passing evidence',async t=>{const directory=await fixture(t);await put(directory,'20261024000000',pass);await put(directory,'20261026000000',{...pass,results:[{status:'FAIL'}]});await assert.rejects(selectMigrationReport({directory,fingerprint:'current'}),/failed or is incomplete/)})
test('unfinished latest run never falls back',async t=>{const directory=await fixture(t);await put(directory,'20261024000000',pass);await put(directory,'20261026000000');await assert.rejects(selectMigrationReport({directory,fingerprint:'current'}),/ENOENT/)})
test('rejects different source fingerprint',async t=>{const directory=await fixture(t);await put(directory,'20261026000000',pass);await assert.rejects(selectMigrationReport({directory,fingerprint:'changed'}),/fingerprint/)})
test('explicit run is validated and empty results are rejected',async t=>{const directory=await fixture(t);const reportPath=await put(directory,'20261026000000',pass);assert.equal((await selectMigrationReport({reportPath,fingerprint:'current'})).path,reportPath);await writeFile(reportPath,JSON.stringify({...pass,results:[]}));await assert.rejects(selectMigrationReport({reportPath,fingerprint:'current'}),/incomplete/)})
