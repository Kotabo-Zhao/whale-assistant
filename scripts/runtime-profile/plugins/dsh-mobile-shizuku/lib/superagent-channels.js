import { createHash } from 'node:crypto'
import { githubTrigger, githubIssueTrigger, githubReviewTrigger, feishuTrigger } from './superagent-sources.js'

// These adapters do nothing until an operator supplies enabled source configuration and a credential provider.
// The caller advances its durable cursor only after every returned trigger is committed to the domain.
export class SuperagentChannels {
  constructor({ configuration, credentials, fetch: request = globalThis.fetch }) {
    this.configuration = configuration; this.credentials = credentials; this.fetch = request
  }
  async request(url, token, options = {}) {
    const response = await this.fetch(url, { ...options, redirect: 'error', headers: { accept: 'application/json',
      authorization: `Bearer ${token}`, ...(options.body ? { 'content-type': 'application/json' } : {}), ...options.headers } })
    if (!response.ok) throw new Error(`渠道请求失败 HTTP ${response.status}`)
    return response.json()
  }
  async githubComments(since) {
    const config = this.configuration().github
    if (!config?.enabled) return []
    if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(config.repo || '') || !/^[A-Za-z0-9-]+$/.test(config.botLogin || '')) throw new Error('GitHub 来源配置无效')
    const token = await this.credentials('github')
    if (!token) throw new Error('GitHub 凭据不可用')
    const triggers = []
    for (let page = 1;; page++) {
      const url = new URL(`https://api.github.com/repos/${config.repo}/issues/comments`)
      url.searchParams.set('per_page', '100'); url.searchParams.set('page', String(page))
      url.searchParams.set('sort', 'updated'); url.searchParams.set('direction', 'asc')
      if (since) url.searchParams.set('since', since)
      const items = await this.request(url, token, { headers: { accept: 'application/vnd.github+json' } })
      if (!Array.isArray(items)) throw new Error('GitHub 响应无效')
      for (const comment of items) {
        const base = `https://api.github.com/repos/${config.repo}/issues/`
        if (typeof comment.issue_url !== 'string' || !comment.issue_url.toLowerCase().startsWith(base.toLowerCase())) throw new Error('GitHub 事件不属于配置的仓库')
        const issue = Number(comment.issue_url.slice(base.length))
        const trigger = githubTrigger(config.repo, issue, comment, config.botLogin)
        if (trigger) triggers.push(trigger)
      }
      if (items.length < 100) break
    }
    return triggers
  }
  async githubIssuesAndReviews(since) {
    const config = this.configuration().github
    if (!config?.enabled) return []
    if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(config.repo || '') || !/^[A-Za-z0-9-]+$/.test(config.botLogin || '')) throw new Error('GitHub 来源配置无效')
    const token = await this.credentials('github'); if (!token) throw new Error('GitHub 凭据不可用')
    const triggers = []
    for (const kind of ['issues', 'pulls/comments']) for (let page = 1;; page++) {
      const url = new URL(`https://api.github.com/repos/${config.repo}/${kind}`)
      url.searchParams.set('per_page', '100'); url.searchParams.set('page', String(page))
      url.searchParams.set('sort', 'updated'); url.searchParams.set('direction', 'asc')
      if (kind === 'issues') url.searchParams.set('state', 'all')
      if (since) url.searchParams.set('since', since)
      const items = await this.request(url, token, { headers: { accept: 'application/vnd.github+json' } })
      if (!Array.isArray(items)) throw new Error('GitHub 响应无效')
      for (const item of items) {
        let trigger
        if (kind === 'issues') trigger = githubIssueTrigger(config.repo, item, config.botLogin)
        else {
          const prefix = `https://api.github.com/repos/${config.repo}/pulls/`
          if (typeof item.pull_request_url !== 'string' || !item.pull_request_url.toLowerCase().startsWith(prefix.toLowerCase())) throw new Error('GitHub 评审不属于配置的仓库')
          trigger = githubReviewTrigger(config.repo, Number(item.pull_request_url.slice(prefix.length)), item, config.botLogin)
        }
        if (trigger) triggers.push(trigger)
      }
      if (items.length < 100) break
    }
    return triggers
  }
  async feishuDiscoverThread(chat, messageId) {
    const config = this.configuration().feishu
    if (!config?.enabled || !config.chats?.includes(chat) || !/^om_[A-Za-z0-9_-]+$/.test(messageId || '')) throw new Error('飞书话题未授权')
    const token = await this.credentials('feishu'); if (!token) throw new Error('飞书凭据不可用')
    const result = await this.request(`https://open.feishu.cn/open-apis/im/v1/messages/${messageId}`, token)
    if (result.code !== 0 || !Array.isArray(result.data?.items)) throw new Error(`飞书话题查询失败 ${result.code ?? 'invalid'}`)
    const item = result.data.items.find(entry => entry.message_id === messageId)
    if (!item || item.chat_id !== chat) throw new Error('飞书话题不属于已授权群')
    return item.thread_id || undefined
  }
  async feishuHistory(chat, startTime, threadId, rootId) {
    const config = this.configuration().feishu
    if (!config?.enabled) return []
    if (!Array.isArray(config.chats) || !config.chats.includes(chat) || !/^ou_[A-Za-z0-9_-]+$/.test(config.botOpenId || '')) throw new Error('飞书会话未授权')
    if (threadId && !/^omt?_[A-Za-z0-9_-]+$/.test(threadId)) throw new Error('飞书话题标识无效')
    const token = await this.credentials('feishu')
    if (!token) throw new Error('飞书凭据不可用')
    const triggers = []; let pageToken
    do {
      const url = new URL('https://open.feishu.cn/open-apis/im/v1/messages')
      url.searchParams.set('container_id_type', threadId ? 'thread' : 'chat'); url.searchParams.set('container_id', threadId || chat)
      url.searchParams.set('sort_type', 'ByCreateTimeAsc'); url.searchParams.set('page_size', '50')
      if (startTime) url.searchParams.set('start_time', String(startTime))
      if (pageToken) url.searchParams.set('page_token', pageToken)
      const result = await this.request(url, token)
      if (result.code !== 0 || !Array.isArray(result.data?.items)) throw new Error(`飞书读取失败 ${result.code ?? 'invalid'}`)
      for (const message of result.data.items) {
        if (message.chat_id !== chat) throw new Error('飞书返回了不属于所选群的消息')
        const normalized = threadId ? { ...message, root_id: message.root_id || rootId, thread_id: threadId } : message
        const trigger = feishuTrigger({ message: normalized, sender: message.sender }, config.botOpenId)
        if (trigger) triggers.push(trigger)
      }
      pageToken = result.data.has_more ? result.data.page_token : undefined
      if (result.data.has_more && !pageToken) throw new Error('飞书分页游标缺失')
    } while (pageToken)
    return triggers
  }
  async send(message) {
    const address = message.channel.address
    if (message.channel.source === 'github') {
      const config = this.configuration().github
      if (!config?.enabled || config.repo?.toLowerCase() !== address.repo?.toLowerCase() || !/^\d+$/.test(address.issue || '')) throw new Error('GitHub 目标未授权')
      const token = await this.credentials('github'); if (!token) throw new Error('GitHub 凭据不可用')
      const marker = `<!-- dsh-superagent:${message.id} -->`
      if (address.review && !/^\d+$/.test(address.review)) throw new Error('GitHub 评审目标无效')
      const endpoint = address.review ? `https://api.github.com/repos/${config.repo}/pulls/${address.issue}/comments` : `https://api.github.com/repos/${config.repo}/issues/${address.issue}/comments`
      const sendEndpoint = address.review ? `${endpoint}/${address.review}/replies` : endpoint
      // Search all pages before retrying a possibly successful write, using the configured bot identity.
      for (let page = 1;; page++) {
        const items = await this.request(`${endpoint}?per_page=100&page=${page}`, token, { headers: { accept: 'application/vnd.github+json' } })
        if (!Array.isArray(items)) throw new Error('GitHub 回执查询失败')
        if (items.some(item => item.user?.login?.toLowerCase() === config.botLogin.toLowerCase() && item.body?.includes(marker))) return
        if (items.length < 100) break
      }
      await this.request(sendEndpoint, token, { method: 'POST', headers: { accept: 'application/vnd.github+json' }, body: JSON.stringify({ body: `${message.text}\n\n${marker}` }) })
    } else if (message.channel.source === 'feishu') {
      const config = this.configuration().feishu
      if (!config?.enabled || !config.chats?.includes(address.chat) || !/^om_[A-Za-z0-9_-]+$/.test(address.message || '')) throw new Error('飞书目标未授权')
      const token = await this.credentials('feishu'); if (!token) throw new Error('飞书凭据不可用')
      const uuid = createHash('sha256').update(message.id).digest('hex').slice(0, 32)
      const result = await this.request(`https://open.feishu.cn/open-apis/im/v1/messages/${address.message}/reply`, token,
        { method: 'POST', body: JSON.stringify({ msg_type: 'text', content: JSON.stringify({ text: message.text }), uuid }) })
      if (result.code !== 0) throw new Error(`飞书发送失败 ${result.code ?? 'invalid'}`)
    } else throw new Error('未知消息渠道')
  }
}
