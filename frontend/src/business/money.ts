/** Parse decimal input without multiplying a floating point yuan value. */
export function yuanToFen(input: string, allowZero = false): number {
  const value = input.trim()
  if (!/^\d+(?:\.\d{1,2})?$/.test(value)) throw new Error('请输入最多两位小数的金额')
  const [whole = '', fraction = ''] = value.split('.')
  const fen = Number(whole) * 100 + Number(fraction.padEnd(2, '0'))
  if (!Number.isSafeInteger(fen) || fen < (allowZero ? 0 : 1) || fen > 1000000) throw new Error('小时价格须在' + (allowZero ? '0' : '0.01') + '至10000元之间')
  return fen
}
export function fenToYuan(fen: number): string { return `${Math.floor(fen / 100)}.${String(fen % 100).padStart(2, '0')}` }
