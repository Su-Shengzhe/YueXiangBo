import { readFile, writeFile } from 'node:fs/promises'
const file='docs/contracts/openapi-m1.json', spec=JSON.parse(await readFile(file,'utf8'))
spec.info={...spec.info,title:'悦享泊业务接口：M1、资源管理与M2预约恢复',version:'0.4.0',description:'新增预约请求找回、入离场事件和物业无法履约；保留既有接口兼容。'}
const ref=name=>({$ref:'#/components/schemas/'+name})
spec.components.schemas.ParkingEventInput={type:'object',required:['reservationId','type','eventId'],properties:{reservationId:{type:'string',pattern:'^[0-9]+$'},type:{type:'string',enum:['ENTRY','EXIT']},eventId:{type:'string',pattern:'^(?!legacy_)[A-Za-z0-9_-]{8,80}$'}}}
spec.components.schemas.UnavailableInput={type:'object',required:['reason'],properties:{reason:{type:'string',minLength:1,maxLength:500}}}
const clone=value=>structuredClone(value)
const original=spec.paths['/property/reservations/{reservationId}/entry'].post
const entry=clone(original);entry.operationId='createParkingEvent';entry.summary='提交入离场事件；同事件返回首次结果，当前状态另行查询';entry.parameters=[];entry.requestBody={required:true,content:{'application/json':{schema:ref('ParkingEventInput')}}}
spec.paths['/parking-events']={post:entry}
const unavailable=clone(original);unavailable.operationId='confirmUnavailable';unavailable.summary='授权社区物业确认未入场预约无法履约，关闭并异步退款';unavailable.requestBody={required:true,content:{'application/json':{schema:ref('UnavailableInput')}}}
spec.paths['/property/reservations/{reservationId}/unavailable']={post:unavailable}
const recovery=clone(spec.paths['/reservations/{reservationId}'].get);recovery.operationId='findReservationByRequestKey';recovery.summary='读取本人已提交的原预约结果；404不能证明原请求已失败';recovery.parameters=[{name:'requestKey',in:'path',required:true,schema:{type:'string',pattern:'^[A-Za-z0-9_-]{8,80}$'}}]
spec.paths['/reservations/by-request-key/{requestKey}']={get:recovery}
await writeFile(file,JSON.stringify(spec,null,2)+'\n')
