// Minimal host contract used by integration tests. Assertions live in the tests.
export function delegationFixture(ctx, makeAgent) {
  const live = new Map()
  ctx.sessionPersistence.stat = async () => undefined
  ctx.agents = { get: id => live.get(id), resume: async () => { throw new Error("unexpected cold resume") }, create: async ({ sessionId, setup }) => {
    const child = makeAgent(sessionId)
    child.ctx.get ??= name => name==='workingDirectory' ? {set:async()=>{}} : undefined
    await setup(child.ctx,child)
    live.set(sessionId, child)
    return { agent: child }
  } }
  return {
    captureDelegatedPolicyOverrides(parent) {
      const preset = ctx.permissionPresets.current(parent.session)
      return { sandboxMode: ctx.permissionPresets.resolve?.(preset)?.sandbox ?? preset,
        approvalPolicy: 'never', ...(['auto','danger-full-access'].includes(preset) ? { permissionPreset: preset } : {}) }
    },
    appendDelegatedPolicyOverrides(session, policy) {
      session.append?.('sandbox/mode',{ mode: policy.sandboxMode, source: 'delegation' })
      session.append?.('approval/policy',{ policy: 'never', source: 'delegation' })
    },
  }
}
