const zone = 'Asia/Shanghai'
export function businessDate(value: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { timeZone: zone, hour12: false }) : '—'
}
export function businessInput(value: number) {
  const parts = new Intl.DateTimeFormat('en-GB', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(value))
  const part = (name: string) => parts.find(p => p.type === name)!.value
  return part('year') + '-' + part('month') + '-' + part('day') + 'T' + part('hour') + ':' + part('minute')
}
export function businessInstant(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) throw new Error('请输入完整的北京时间')
  const instant = new Date(value + ':00+08:00')
  if (!Number.isFinite(instant.getTime()) || businessInput(instant.getTime()) !== value) throw new Error('日期时间无效')
  return instant.toISOString()
}
