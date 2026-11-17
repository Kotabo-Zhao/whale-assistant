/**
 * 一次输入投到 Worker 之后，回执只有四种可判断的结果。
 * sending / unknown 表示发送已经开始，但进程在回执落盘前中断：不能当成失败重放，也不能当成成功。
 */
export function deliveryDisposition(input) {
  if (!input) return 'missing'
  if (input.delivery === 'delivered') return 'delivered'
  if (input.delivery === 'unknown' || input.delivery === 'sending') return 'uncertain'
  return 'pending'
}

/**
 * 按已有回执决定每个域输入是否还能发送一次。
 * send 只应在 missing 时被调用；返回 uncertain 表示调用方确认这次不能再发。
 * 已是 uncertain 的输入不会进入 send。
 */
export async function reconcileDeliveries(inputs, ids, send) {
  const deliveredIds = []
  const uncertain = []
  const pending = []
  for (const id of ids) {
    let input = inputs.find(item => item.commandId === `domain:${id}`)
    let disposition = deliveryDisposition(input)
    if (disposition === 'missing') {
      const outcome = await send(id)
      if (outcome === 'uncertain' || outcome === 'skip') {
        if (outcome === 'uncertain') uncertain.push(id)
        else deliveredIds.push(id)
        continue
      }
      input = inputs.find(item => item.commandId === `domain:${id}`)
      disposition = deliveryDisposition(input)
    }
    if (disposition === 'delivered') deliveredIds.push(id)
    else if (disposition === 'uncertain') uncertain.push(id)
    else pending.push(id)
  }
  return { deliveredIds, uncertain, pending }
}
