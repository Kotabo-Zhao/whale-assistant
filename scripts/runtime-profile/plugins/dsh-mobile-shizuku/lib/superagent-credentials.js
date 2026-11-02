// Source secrets are supplied by the host's private credential provider and never written in domain snapshots.
export function createCredentialProvider(readSecret, request = globalThis.fetch) {
  let feishuToken, expiresAt = 0
  return async source => {
    if (source === 'github') return readSecret('githubToken')
    if (source !== 'feishu') throw new Error('未知凭据来源')
    if (feishuToken && Date.now() < expiresAt) return feishuToken
    const appId = await readSecret('feishuAppId'), appSecret = await readSecret('feishuAppSecret')
    if (!appId || !appSecret) throw new Error('飞书应用凭据不可用')
    const response = await request('https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal', {
      method: 'POST', redirect: 'error', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ app_id: appId, app_secret: appSecret }),
    })
    if (!response.ok) throw new Error(`飞书身份验证失败 HTTP ${response.status}`)
    const result = await response.json()
    if (result.code !== 0 || typeof result.tenant_access_token !== 'string' || typeof result.expire !== 'number') throw new Error(`飞书身份验证失败 ${result.code ?? 'invalid'}`)
    feishuToken = result.tenant_access_token; expiresAt = Date.now() + Math.max(0, result.expire - 120) * 1000
    return feishuToken
  }
}
