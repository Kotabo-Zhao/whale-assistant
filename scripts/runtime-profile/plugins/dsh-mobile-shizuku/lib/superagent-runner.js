import { existsSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

/** Ingest and deliver only after source configuration is supplied by the host. */
export class SuperagentSourceRunner {
  constructor(directory, domain, channels, configuration) {
    this.domain = domain; this.channels = channels; this.configuration = configuration
    this.path = join(directory, 'source-cursors.json')
    this.cursors = existsSync(this.path) ? JSON.parse(readFileSync(this.path, 'utf8')) : {}
    this.busy = false
    this.errors = {}
    this.nextChecks = 0
  }
  saveCursor(name, value) {
    const next = { ...this.cursors, [name]: value }
    writeFileSync(`${this.path}.tmp`, JSON.stringify(next), { mode: 0o600 })
    renameSync(`${this.path}.tmp`, this.path)
    this.cursors = next
  }
  async poll() {
    if (this.busy) return
    this.busy = true
    try {
      const config = this.configuration()
      if (!config.github?.enabled) for (const key of Object.keys(this.errors)) if (key === 'github' || key.startsWith('github-checks:') || key.startsWith('github-reviews:')) delete this.errors[key]
      if (!config.feishu?.enabled) for (const key of Object.keys(this.errors)) if (key.startsWith('feishu:') || key.startsWith('feishu-thread:')) delete this.errors[key]
      if (config.github?.enabled) try {
        const key = `github:${config.github.repo.toLowerCase()}`
        // An overlap avoids losing updates with the same timestamp; Trigger ids deduplicate re-reads.
        const since = this.cursors[key] || config.github.since || new Date(Date.now() - 30000).toISOString()
        const started = new Date().toISOString()
        const updates = await this.channels.githubIssuesAndReviews(since)
        const triggers = [...await this.channels.githubComments(since), ...updates]
        for (const trigger of triggers) {
          if (trigger.meta?.edited && (!this.domain.isAllowed(trigger.source, trigger.actor.id)
            || !trigger.threads.some(thread => this.domain.repo.threads.get(`${thread.source}:${thread.id}`)?.subscriptions?.length))) continue
          this.domain.receive(trigger)
        }
        for (const closure of updates.closures ?? []) {
          const thread = this.domain.repo.threads.get(`${closure.thread.source}:${closure.thread.id}`)
          if (thread?.subscriptions?.length) this.domain.engine.threadClosed(closure.thread, closure.outcome, closure.at)
        }
        this.saveCursor(key, new Date(Date.parse(started) - 60000).toISOString())
        delete this.errors.github
      } catch (error) { this.errors.github = String(error?.message ?? error) }
      if (config.github?.enabled && this.channels.githubChecks && Date.now() >= this.nextChecks) {
        this.nextChecks = Date.now() + 60000
        const pulls = new Set()
        for (const trigger of this.domain.repo.triggerRows.values()) {
          if (trigger.source !== 'github' || !trigger.meta?.pull || trigger.meta.repo?.toLowerCase() !== config.github.repo?.toLowerCase()) continue
          const thread = this.domain.repo.threads.get(`github:${config.github.repo.toLowerCase()}#${trigger.meta.issue}`)
          if (thread?.subscriptions?.some(id => { const ticket = this.domain.repo.tickets.get(id); return ticket && !ticket.ended })) pulls.add(trigger.meta.issue)
        }
        for (const pull of pulls) try {
          for (const trigger of await this.channels.githubChecks(pull)) this.domain.receive(trigger)
          delete this.errors[`github-checks:${pull}`]
        } catch (error) { this.errors[`github-checks:${pull}`] = String(error?.message ?? error) }
        if (this.channels.githubReviews) for (const pull of pulls) try {
          for (const trigger of await this.channels.githubReviews(pull)) this.domain.receive(trigger)
          delete this.errors[`github-reviews:${pull}`]
        } catch (error) { this.errors[`github-reviews:${pull}`] = String(error?.message ?? error) }
      }
      if (config.feishu?.enabled) for (const chat of config.feishu.chats ?? []) try {
        const key = `feishu:${chat}`
        const since = this.cursors[key] || config.feishu.startTime || Math.floor(Date.now() / 1000) - 30
        const started = Math.floor(Date.now() / 1000)
        const triggers = await this.channels.feishuHistory(chat, since)
        for (const trigger of triggers) this.domain.receive(trigger)
        this.saveCursor(key, Math.max(0, started - 60))
        delete this.errors[`feishu:${chat}`]
      } catch (error) { this.errors[`feishu:${chat}`] = String(error?.message ?? error) }
      if (config.feishu?.enabled) {
        const topics = new Map()
        for (const trigger of this.domain.repo.triggerRows.values()) {
          const { chat, root, threadId } = trigger.meta ?? {}
          if (trigger.source !== 'feishu' || !config.feishu.chats?.includes(chat) || !root) continue
          const thread = this.domain.repo.threads.get(`feishu:${chat}:${root}`)
          if (!thread?.subscriptions?.length) continue
          const hintKey = `feishu-topic:${chat}:${root}`
          let topicId = threadId || this.cursors[hintKey]
          if (!topicId) try {
            topicId = await this.channels.feishuDiscoverThread(chat, root)
            if (topicId) this.saveCursor(hintKey, topicId)
          } catch (error) { this.errors[hintKey] = String(error?.message ?? error) }
          if (topicId) topics.set(topicId, { chat, root, at: trigger.at })
        }
        for (const [threadId, topic] of topics) try {
          const key = `feishu-thread:${threadId}`
          const since = this.cursors[key] || Math.floor(Date.parse(topic.at) / 1000)
          const started = Math.floor(Date.now() / 1000)
          for (const trigger of await this.channels.feishuHistory(topic.chat, since, threadId, topic.root)) this.domain.receive(trigger)
          this.saveCursor(key, Math.max(0, started - 60))
          delete this.errors[key]
        } catch (error) { this.errors[`feishu-thread:${threadId}`] = String(error?.message ?? error) }
      }
      this.domain.engine.evaluateDue()
      const blockedChannels = new Set()
      for (const message of this.domain.pendingMessages()) {
        const channel = `${message.channel?.source ?? ''}:${message.channel?.thread ?? message.ticketId}`
        if (blockedChannels.has(channel)) continue
        if ((message.nextAttemptAt ?? 0) > Date.now()) { blockedChannels.add(channel); continue }
        try { await this.channels.send(message); this.domain.markMessage(message.id, 'sent') }
        catch (error) { this.domain.failMessage(message.id, error); blockedChannels.add(channel) }
      }
    } finally { this.busy = false }
  }
}
