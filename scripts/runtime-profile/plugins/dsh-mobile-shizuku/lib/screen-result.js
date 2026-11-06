/** Keep coordinates, leases and errors, but send OCR text only once to the model. */
export function compactScreenResult(result) {
  if (result.truncated) return result
  let value
  try { value = JSON.parse(result.output) } catch { return result }
  for (const observation of [value, value?.observation]) {
    const image = observation?.image
    if (image && Array.isArray(image.lines)) {
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
