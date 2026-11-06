/** Keep coordinates, leases and errors, but send OCR text only once to the model. */
import { createHash, randomUUID } from 'node:crypto'

/** Stable for a replayed Host tool call, independent of model-supplied arguments. */
export function screenOperation(exec) {
  const sessionId = exec.agent?.session?.id || exec.agent?.id
  const callId = exec.callId
  if (typeof sessionId !== 'string' || typeof callId !== 'string' || !sessionId || !callId) return { operationId: randomUUID() }
  if (sessionId.length > 160 || callId.length > 160) throw new Error('SCREEN_OPERATION_ID_INVALID')
  const hex = createHash('sha256').update(JSON.stringify(['whale-screen-v1', sessionId, callId])).digest('hex')
  const operationId = `${hex.slice(0,8)}-${hex.slice(8,12)}-5${hex.slice(13,16)}-8${hex.slice(17,20)}-${hex.slice(20,32)}`
  return { operationId, sessionId, callId }
}

export function compactScreenResult(result) {
  if (result.truncated) return result
  let value
  try { value = JSON.parse(result.output) } catch { return result }
  for (const observation of [value, value?.observation]) {
    const image = observation?.image
    if (image && Array.isArray(image.lines)) {
      // OCR was already projected from the decoder bitmap into physical display pixels.
      // Decoder dimensions are diagnostics, not another coordinate system for the model to scale.
      observation.coordinateGuide = `OCR lines/elements 的 bounds 已是 ${observation.width || image.width}×${observation.height || image.height} 主屏物理坐标，直接取中心使用，禁止再乘截图缩放倍数。有文字的按钮优先用 mobile_screen_sequence 单步按文字定位；弹窗或键盘变化后不要沿用旧位置。`
      image.coordinateSpace = 'physical_display_pixels'
      delete image.decodedWidth
      delete image.decodedHeight
      delete image.ocrText
      delete image.frameId
      delete image.captureRequestedAtMs
    }
  }
  return { ...result, output: JSON.stringify(value) }
}

export function screenSequenceRequest(args) {
  if (typeof args.packageName !== 'string' || args.packageName.length > 160 ||
      !/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/.test(args.packageName) ||
      !Array.isArray(args.steps) || args.steps.length < 1 || args.steps.length > 6 ||
      args.steps.some(step => !step || Object.keys(step).length !== 1 ||
        typeof step.text !== 'string' || !step.text.trim() || step.text.length > 80)) throw new Error('SCREEN_INPUT_INVALID')
  return { packageName: args.packageName, steps: args.steps.map(({ text }) => ({ text })) }
}
