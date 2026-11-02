// Normalize source-native identities and addresses without treating external content as authority.
const githubName = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/
const loginName = /^[A-Za-z0-9-]+$/
const issueNumber = value => Number.isSafeInteger(value) && value > 0
export function githubTrigger(repo, issue, comment, botLogin) {
  if (!githubName.test(repo) || !issueNumber(issue) || !issueNumber(comment?.id) || !loginName.test(botLogin)) throw new Error('GitHub 事件无效')
  const login = comment.user?.login
  if (!loginName.test(login || '') || login.toLowerCase() === botLogin.toLowerCase()) return null
  const text = typeof comment.body === 'string' ? comment.body : ''
  const addressed = new RegExp(`(^|[^A-Za-z0-9-])@${botLogin}(?![A-Za-z0-9-])`, 'i').test(text)
  return { id: `github:${repo.toLowerCase()}:comment:${comment.id}`, source: 'github',
    threads: [{ source: 'github', id: `${repo.toLowerCase()}#${issue}` }], actor: { source: 'github', id: login },
    human: comment.user?.type === 'User', ...(addressed ? { addressed: 'mention' } : {}), at: comment.created_at,
    text, channel: { source: 'github', thread: `${repo.toLowerCase()}#${issue}`, address: { repo, issue: String(issue), comment: String(comment.id) } },
    meta: { repo, issue, comment: comment.id } }
}

export function githubIssueTrigger(repo, issue, botLogin) {
  if (!githubName.test(repo) || !issueNumber(issue?.number) || !issueNumber(issue?.id) || !loginName.test(botLogin)) throw new Error('GitHub 工单事件无效')
  const login = issue.user?.login
  if (!loginName.test(login || '') || login.toLowerCase() === botLogin.toLowerCase()) return null
  const text = `${issue.title || ''}\n\n${issue.body || ''}`
  const addressed = new RegExp(`(^|[^A-Za-z0-9-])@${botLogin}(?![A-Za-z0-9-])`, 'i').test(text)
  const thread = `${repo.toLowerCase()}#${issue.number}`
  return { id: `github:${repo.toLowerCase()}:issue:${issue.id}`, source: 'github', threads: [{ source: 'github', id: thread }],
    actor: { source: 'github', id: login }, human: issue.user?.type === 'User', ...(addressed ? { addressed: 'mention' } : {}),
    at: issue.created_at, text, channel: { source: 'github', thread, address: { repo, issue: String(issue.number) } },
    meta: { repo, issue: issue.number, pull: !!issue.pull_request } }
}
export function githubReviewTrigger(repo, pull, comment, botLogin) {
  const trigger = githubTrigger(repo, pull, comment, botLogin)
  if (!trigger) return null
  const root = comment.in_reply_to_id || comment.id
  if (!issueNumber(root)) throw new Error('GitHub 评审线程无效')
  const issueThread = `${repo.toLowerCase()}#${pull}`
  const reviewThread = `${issueThread}:review:${root}`
  trigger.id = `github:${repo.toLowerCase()}:review:${comment.id}`
  trigger.threads = [{ source: 'github', id: reviewThread }, { source: 'github', id: issueThread }]
  trigger.channel = { source: 'github', thread: reviewThread, address: { repo, issue: String(pull), review: String(root) } }
  trigger.meta.review = root
  return trigger
}
export function feishuTrigger(event, botOpenId) {
  const message = event?.message, sender = event?.sender
  const chat = message?.chat_id, id = message?.message_id, actor = sender?.sender_id?.open_id || sender?.id
  if (sender?.sender_type !== 'user') return null
  if (!/^oc_[A-Za-z0-9_-]+$/.test(chat || '') || !/^om_[A-Za-z0-9_-]+$/.test(id || '') || !/^ou_[A-Za-z0-9_-]+$/.test(actor || '')) throw new Error('飞书事件无效')
  if (actor === botOpenId || message.deleted || (message.message_type && message.message_type !== 'text') || (message.msg_type && message.msg_type !== 'text')) return null
  let text
  try { text = JSON.parse(message.content ?? message.body?.content ?? '').text } catch { return null }
  if (typeof text !== 'string') throw new Error('飞书文本消息无效')
  const root = message.root_id || id
  const direct = message.chat_type === 'p2p'
  const addressed = direct ? 'direct' : (message.mentions || []).some(item => (typeof item.id === 'string' ? item.id : item.id?.open_id) === botOpenId) ? 'mention' : undefined
  return { id: `feishu:${id}`, source: 'feishu', threads: [{ source: 'feishu', id: `${chat}:${root}` }],
    actor: { source: 'feishu', id: actor }, human: sender.sender_type === 'user', ...(addressed ? { addressed } : {}),
    at: new Date(Number(message.create_time)).toISOString(), text,
    channel: { source: 'feishu', thread: `${chat}:${root}`, address: { chat, message: id, root } },
    meta: { chat, root, message: id, direct, ...(message.thread_id ? { threadId: message.thread_id } : {}) } }
}
