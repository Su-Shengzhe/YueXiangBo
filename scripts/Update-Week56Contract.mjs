import { readFile, writeFile } from 'node:fs/promises'
const path='docs/contracts/openapi-m1.json',spec=JSON.parse(await readFile(path,'utf8'))
spec.info={...spec.info,title:'悦享泊业务接口基线：M1与第5—6周资源管理',version:'0.3.0',description:'2026-10-24。注册、多角色、版本化授权、资源管理、报价兼容与简化停车交易。正式结算对账和真实AI仍为后续阶段。保留文件名以兼容已有文档链接。'}
const S=spec.components.schemas, str={type:'string'}, id={type:'string',pattern:'^[0-9]+$'}, bool={type:'boolean'}, integer={type:'integer'}, version={type:'integer',minimum:0}, date={type:'string',format:'date-time'}, fen={type:'integer',minimum:1,maximum:1000000}
const ref=n=>({$ref:'#/components/schemas/'+n}), array=items=>({type:'array',items}), obj=(properties,required=Object.keys(properties))=>({type:'object',properties,required})
const pageOf=n=>obj({items:array(ref(n)),total:{type:'integer',minimum:0},page:{type:'integer',minimum:1},size:{type:'integer',minimum:1,maximum:100}})
const roles=array({type:'string',enum:['DRIVER','OWNER','PROPERTY','PLATFORM']})
S.RegisterInput={...obj({username:{type:'string',pattern:'^[A-Za-z0-9_]{4,40}$'},password:{type:'string',minLength:10,description:'UTF-8编码不超过72字节'}}),additionalProperties:false}
S.Registered=obj({id,username:str,roles});S.User.properties.permissions=array(str)
S.ManagedUser=obj({id,username:str,enabled:bool,version,roles,communities:array(id)})
S.ManagedCommunity=obj({id,name:str,address:str,enabled:bool,version})
S.ManagedVehicle=obj({id,plate:str,enabled:bool,version})
S.ManagedSpace=obj({id,communityId:id,name:str,spaceCode:str,locationDesc:str,spaceType:{type:'string',enum:['STANDARD','COMPACT','EV']},proofText:str,reviewStatus:{type:'string',enum:['DRAFT','PENDING','APPROVED','REJECTED']},reviewComment:str,enabled:bool,version})
S.ShareWindow=obj({id,spaceId:id,startAt:date,endAt:date,hourlyFen:fen,enabled:bool,version})
S.Authorization=obj({communityId:id,version,userIds:array(id),users:array(obj({id,username:str,enabled:bool}))})
S.AuditEntry=obj({id,actorId:id,action:str,resourceId:id,createdAt:date,detailJson:{type:['string','null']}})
S.SearchSpace=obj({id,name:str,communityId:id,locationDesc:str,spaceType:str,windowId:id,windowVersion:version,hourlyFen:fen,estimatedFen:{type:'integer',minimum:0}})
for(const n of ['ManagedUser','ManagedCommunity','ManagedVehicle','ManagedSpace','ShareWindow','AuditEntry','SearchSpace'])S[n+'Page']=pageOf(n)
S.CommunityInput=obj({name:{type:'string',minLength:1,maxLength:100},address:{type:'string',minLength:1,maxLength:255}})
S.CommunityEdit=obj({...S.CommunityInput.properties,enabled:bool,version},['version'])
S.VehicleInput=obj({plate:{type:'string',description:'去空白并转大写；普通或新能源车牌，全局唯一'}})
S.VehicleEdit=obj({plate:str,enabled:bool,version},['version'])
S.SpaceInput=obj({communityId:id,spaceCode:{type:'string',minLength:1,maxLength:60},locationDesc:{type:'string',minLength:1,maxLength:255},spaceType:S.ManagedSpace.properties.spaceType,proofText:{type:'string',minLength:1,maxLength:1000}})
const {communityId,...spaceEdit}=S.SpaceInput.properties;S.SpaceEdit=obj({...spaceEdit,version})
S.WindowInput=obj({startAt:date,endAt:date,hourlyFen:fen});S.WindowInput.description='未来30天，15分钟对齐；同车位半开区间不重叠。'
S.WindowEdit=obj({...S.WindowInput.properties,enabled:bool,version},['version']);S.WindowEdit.dependentRequired={startAt:['endAt','hourlyFen'],endAt:['startAt','hourlyFen'],hourlyFen:['startAt','endAt']}
S.VersionInput=obj({version});S.ReviewInput=obj({version,decision:{type:'string',enum:['APPROVE','REJECT']},comment:{type:'string',minLength:1,maxLength:500}})
S.SpaceStatusInput=obj({version,enabled:bool,reason:{type:'string',minLength:1,maxLength:500}})
S.UserRolesInput=obj({version,roles:{...roles,minItems:1}});S.UserStatusInput=obj({version,enabled:bool});S.AuthorizationInput=obj({version,userIds:{...array(id),maxItems:100}})
const quote=['windowId','windowVersion','expectedHourlyFen','expectedPrepayFen']
Object.assign(S.ReservationInput.properties,{windowId:id,windowVersion:version,expectedHourlyFen:fen,expectedPrepayFen:{type:'integer',minimum:0}})
S.ReservationInput.required=S.ReservationInput.required.filter(k=>!quote.includes(k))
S.ReservationInput.oneOf=[{required:quote},{not:{anyOf:quote.map(k=>({required:[k]}))}}]
S.ReservationInput.description='新前端提交完整四项报价；部分缺失422 INCOMPLETE_QUOTE；报价变化409 QUOTE_CHANGED。四项全部缺省兼容M1，使用服务器当前价格，不提供客户端报价一致性保护。'
const qp=(name,schema,required=false)=>({in:'query',name,required,schema})
const paging=[qp('page',{type:'integer',minimum:1,default:1,maximum:100000}),qp('size',{type:'integer',minimum:1,maximum:100,default:20})]
const search=[qp('communityId',id,true),qp('startAt',date,true),qp('endAt',date,true),qp('spaceType',{type:'string',enum:['','STANDARD','COMPACT','EV']}),qp('maxHourlyFen',{type:'integer',minimum:0}),...paging]
function op(route,method,summary,input,output,permission,parameters=[]){
 const responses=structuredClone(spec.paths['/reservations'].post.responses)
 responses['200'].content['application/json'].schema.properties.data=typeof output==='string'?ref(output):output
 for(const [status,r] of Object.entries(responses))if(status!=='200')r.description=({'401':'登录失效或撤销','403':'角色/操作/社区范围无权限','404':'资源不存在或非本人','409':'STALE_VERSION、LAST_ADMIN、ACTIVE_RESERVATION、WINDOW_OVERLAP、QUOTE_CHANGED、DUPLICATE_RESOURCE等状态冲突','422':'参数、版本、时间、价格或报价完整性错误','429':'登录或注册限流','500':'服务异常，写结果需查询确认','503':'依赖服务不可用'})[status]||r.description
 const value={summary,description:permission,operationId:method+'_'+route.replace(/[^a-zA-Z0-9]/g,'_'),'x-service':route.startsWith('/auth')||route.startsWith('/admin/users')||route.includes('/administrators')||route.endsWith('/identity')?'identity-service':'parking-service',security:permission==='公开'?[]:[{bearerAuth:[]}],responses,parameters:[...Array.from(route.matchAll(/\{(\w+)\}/g),m=>({in:'path',name:m[1],required:true,schema:id})),...parameters]}
 if(input)value.requestBody={required:true,content:{'application/json':{schema:typeof input==='string'?ref(input):input}}}
 spec.paths[route]??={};spec.paths[route][method]=value
}
op('/auth/register','post','注册普通司机','RegisterInput','Registered','公开')
op('/admin/users','get','分页搜索用户',null,'ManagedUserPage','PLATFORM / USER_MANAGE',[...paging,qp('query',{type:'string',maxLength:40}),qp('role',{type:'string',enum:['','DRIVER','OWNER','PROPERTY','PLATFORM']}),qp('enabled',bool)])
op('/admin/users/{id}/status','patch','启停账号并撤销会话','UserStatusInput',obj({id,enabled:bool,version}),'USER_MANAGE；检查version，保留最后一名启用平台管理员')
op('/admin/users/{id}/roles','put','替换角色集合并撤销会话','UserRolesInput',obj({id,roles,version}),'USER_MANAGE；检查version；撤销PROPERTY同步移除社区授权并递增授权集合版本')
op('/admin/communities/{id}/administrators','get','读取物业授权及版本',null,'Authorization','COMMUNITY_AUTHORIZE；首次未建立集合version为0')
op('/admin/communities/{id}/administrators','put','替换物业授权集合','AuthorizationInput','Authorization','COMMUNITY_AUTHORIZE；仅启用PROPERTY；版本检查；原子创建首次集合；撤销受影响会话')
op('/admin/communities','get','社区管理分页',null,'ManagedCommunityPage','COMMUNITY_MANAGE',paging)
op('/communities','post','创建社区','CommunityInput','ManagedCommunity','COMMUNITY_MANAGE')
op('/communities/{id}','patch','编辑或启停社区','CommunityEdit','ManagedCommunity','COMMUNITY_MANAGE；停用阻止新预约，历史订单继续处理')
op('/property/communities','get','授权物业社区',null,array(obj({id,name:str,address:str,enabled:bool})),'PARKING_OPERATE；物业仅授权社区，平台全部')
op('/manage/vehicles','get','本人车辆分页',null,'ManagedVehiclePage','DRIVER；本人',paging)
op('/vehicles','post','绑定本人车辆','VehicleInput','ManagedVehicle','DRIVER；规范化全局唯一，停用后仍保留绑定')
op('/vehicles/{id}','patch','修改或启停本人车辆','VehicleEdit','ManagedVehicle','DRIVER；本人；有效预约保护')
op('/owner/spaces','get','本人车位分页',null,'ManagedSpacePage','OWNER；本人',paging)
op('/spaces','post','创建车位草稿','SpaceInput','ManagedSpace','OWNER；同社区编号唯一')
op('/spaces/{id}','patch','修改车位并重置草稿','SpaceEdit','ManagedSpace','OWNER；本人；待审核及有效预约时拒绝')
op('/spaces/{id}/submit','post','提交车位审核','VersionInput','ManagedSpace','OWNER；本人；DRAFT或REJECTED')
op('/property/spaces','get','社区车位审核列表',null,'ManagedSpacePage','SPACE_REVIEW且授权社区，或SPACE_MODERATE',[qp('communityId',id,true),...paging])
op('/spaces/{id}/reviews','post','通过或驳回车位','ReviewInput','ManagedSpace','SPACE_REVIEW且授权社区，或SPACE_MODERATE；仅PENDING')
op('/spaces/{id}/status','patch','封禁或恢复车位','SpaceStatusInput','ManagedSpace','授权物业或平台；不改变审核状态，不自动取消历史订单')
op('/spaces/{id}/windows','get','本人车位窗口分页',null,'ShareWindowPage','OWNER；本人',paging)
op('/spaces/{id}/windows','post','新增单次共享窗口','WindowInput','ShareWindow','OWNER；本人且社区启用、车位通过审核并启用')
op('/windows/{id}','patch','修改或启停共享窗口','WindowEdit','ShareWindow','OWNER；本人；有有效预约禁止修改；历史空windowId按区间保护')
op('/spaces/search','get','分页筛选可预约车位',null,'SearchSpacePage','公开',search)
for(const service of ['identity','parking'])op('/admin/audit-logs/'+service,'get','管理操作审计：'+service,null,'AuditEntryPage','AUDIT_READ；仅固定服务来源',paging)
await writeFile(path,JSON.stringify(spec,null,2)+'\n')
console.log('Updated',Object.keys(spec.paths).length,'paths')
