import { parseCatalogData } from './marketCatalog'

const scope = self as unknown as { onmessage: ((event: MessageEvent<ArrayBuffer>) => void) | null; postMessage: (data: unknown) => void }
scope.onmessage = event => {
  try { scope.postMessage({ ok: true, entries: parseCatalogData(new Uint8Array(event.data)) }) }
  catch { scope.postMessage({ ok: false }) }
}
