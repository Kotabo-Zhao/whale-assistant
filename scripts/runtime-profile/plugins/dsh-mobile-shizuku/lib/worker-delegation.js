import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'

let official
export function delegationApi() {
  // Resolve through the installed bundle's dependency graph, rather than a second
  // copy of the Harness permission implementation in this mobile plugin.
  official ??= Promise.resolve().then(() => {
    const require = createRequire(import.meta.url)
    const bundle = createRequire(require.resolve('@deepseek-ai/dsh-base'))
    return import(pathToFileURL(bundle.resolve('@deepseek-ai/dsh-subagent')).href)
  })
  return official
}

export function delegatedWriteAllowed(ctx, parent, captured) {
  const service = parent.ctx.get?.('permissionPresets') ?? ctx.permissionPresets
  let mode = captured.sandboxMode
  if (mode === undefined) {
    try { mode = service.resolve(service.current(parent.session)).sandbox } catch { return false }
  }
  return mode === 'workspace-write' || mode === 'danger-full-access'
}

export function permissionFailure(message) {
  return Object.assign(new Error(message), { code: 'WORKER_SOURCE_PERMISSION' })
}

/** The setup hook runs before the official Agent factory publishes the child. */
export async function prepareDelegatedWorker(ctx, parent, row, api) {
  if (!ctx.agents?.create || !ctx.agents.resume || !ctx.agents.get || !ctx.sessionPersistence?.stat) throw permissionFailure('当前环境缺少安全委派接口，请更新运行环境后继续原任务')
  const captured = api.captureDelegatedPolicyOverrides(parent)
  if (row.mode === 'create' && !delegatedWriteAllowed(ctx,parent,captured))
    throw permissionFailure('来源会话未允许创作，请调整原会话权限后继续此任务')
  const setup = async (childCtx, child) => {
    api.appendDelegatedPolicyOverrides(child.session,captured)
    if(row.workspace) {
      const working = childCtx.get?.('workingDirectory')
      if(!working?.set) throw permissionFailure('当前环境无法隔离创作工作区，请更新运行环境后继续原任务')
      await working.set(child,row.workspace)
    }
  }
  let agent = ctx.agents.get(row.sessionId)
  if (agent) {
    await setup(agent.ctx, agent)
  } else {
    // Delegated sessions must be resumed through their parent-owned registry.
    // A persistence error must propagate before attempting to create a new child.
    const snapshot = await ctx.sessionPersistence.stat(row.sessionId)
    const options = {
      parentAgent: parent,
      agentOptions: { ...row.route },
      setup: async (childCtx, child) => {
        await setup(childCtx, child)
        // Join the parent's current preset generation on each cold attachment.
        // This registers its scoped tools before the worker catalog is narrowed.
        childCtx.get?.('agentPresets')?.composeFrom(childCtx, parent.ctx)
      },
    }
    const handle = snapshot
      ? await ctx.agents.resume({ ...options, resumeSessionId: row.sessionId })
      : await ctx.agents.create({ ...options, sessionId: row.sessionId,
          ...(row.workspace ? { meta: { cwd: row.workspace } } : {}) })
    agent = handle.agent
  }
  await ctx.sessionPersistence.flush()
  return { agent, captured }
}
