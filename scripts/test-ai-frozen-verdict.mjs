import assert from 'node:assert/strict'
import {mkdtempSync,readFileSync,writeFileSync} from 'node:fs'
import {tmpdir} from 'node:os'
import {join} from 'node:path'
import {spawnSync} from 'node:child_process'
import {test} from 'node:test'

const cases=JSON.parse(readFileSync(new URL('../docs/acceptance/ai-m4-cases.json',import.meta.url),'utf8'))
const threshold={query:27,rule:27,bill:18,boundary:20}
const baseline=cases.map(({id,category})=>({id,category,status:Number(id.slice(1))<=threshold[category]?'PASS':'FAIL'}))
const temp=mkdtempSync(join(tmpdir(),'ai-verdict-'))
const races=baseline.filter(row=>['B17','B18'].includes(row.id))
for(const [name,results,success,targeted] of [
 ['at-threshold',baseline,true,false],
 ['below-threshold',baseline.map(row=>row.id==='Q01'?{...row,status:'FAIL'}:row),false,false],
 ['missing-id',baseline.slice(1),false,false],
 ['duplicate-id',[baseline[1],...baseline.slice(1)],false,false],
 ['race-pass',races,true,true],
 ['race-fail',[races[0],{...races[1],status:'FAIL'}],false,true],
 ['race-wrong-id',[races[0],{...races[1],id:'B19'}],false,true],
 ['invalid-input',null,false,false]
]){
 test(name,()=>{
  const file=join(temp,name+'.json')
  writeFileSync(file,JSON.stringify({results}))
  const proc=spawnSync('pwsh',['-NoProfile','-File','scripts/Test-AiFrozen.ps1','-ResultsFile',file,...(targeted?['-BillRaceOnly']:[])],{encoding:'utf8'})
  assert.ifError(proc.error)
  assert.equal(proc.status,success?0:1,proc.stdout+proc.stderr)
  const lines=proc.stdout.trim().split(/\r?\n/)
  const verdict=JSON.parse(lines.find(line=>line.startsWith('{"suite":')))
  assert.equal(verdict.passed,success)
  const path=lines.find(line=>line.startsWith('Report: ')).slice(8)
  assert.deepEqual(JSON.parse(readFileSync(join(path,'summary.json'),'utf8')),verdict)
  assert.deepEqual(JSON.parse(readFileSync(join(path,'results.json'),'utf8')).results,results??[])
 })
}
