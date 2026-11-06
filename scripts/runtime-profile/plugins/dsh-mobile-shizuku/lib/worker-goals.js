import { createHash } from 'node:crypto'
import { mkdirSync } from 'node:fs'
import { loadJSON, persistJSON } from './worker-storage.js'
import { join } from 'node:path'

export const TASK_BUDGET = Object.freeze({ tokens: 200000, calls: 100, milliseconds: 1200000 })
export const GOAL_BUDGET = Object.freeze({ tokens: 600000, calls: 300, milliseconds: 3600000 })
const hash = value => createHash('sha256').update(JSON.stringify(value)).digest('hex')
export function budget(value, defaults) {
  const next = { ...defaults,...value }
  for (const [key,min,max] of [['tokens',100,2000000],['calls',1,2000],['milliseconds',1000,86400000]])
    if (!Number.isSafeInteger(next[key]) || next[key]<min || next[key]>max) throw new Error('预算数值无效：' + key)
  return next
}
export function spent(row, now=Date.now()) {
  const used = { tokens: 0,calls: 0,milliseconds: 0,...row.budgetUsage }
  if (row.state === 'running' && row.budgetStartedAt) used.milliseconds += Math.max(0,now-row.budgetStartedAt)
  return used
}
export function overBudget(used,limit) { return Object.keys(limit).find(key => used[key] >= limit[key]) }
export function chargeElapsed(row,now=Date.now()) {
  row.budgetUsage ??= { tokens:0,calls:0,milliseconds:0 }
  if (row.budgetStartedAt) row.budgetUsage.milliseconds += Math.max(0,now-row.budgetStartedAt)
  delete row.budgetStartedAt
}

/** Outcome/criteria are separate from a worker's self-reported completion. */
export class WorkerGoals {
  constructor(directory, rows) {
    this.rows=rows; this.path=join(directory,'goals','ledger.json')
    mkdirSync(join(directory,'goals'),{ recursive: true })
    const stored=loadJSON(this.path,value=>Array.isArray(value) && value.every(item=>
      Array.isArray(item) && typeof item[0]==='string' && item[1] && typeof item[1].owner==='string' &&
      Array.isArray(item[1].criteria) && item[1].criteria.every(text=>typeof text==='string') &&
      item[1].budget && ['tokens','calls','milliseconds'].every(key=>Number.isSafeInteger(item[1].budget[key]) && item[1].budget[key]>=0) &&
      (item[1].memberIds===undefined || Array.isArray(item[1].memberIds) && item[1].memberIds.every(id=>typeof id==='string')) &&
      (!item[1].recoveryRequired || item[1].recovery && typeof item[1].recovery.incidentId==='string' && typeof item[1].recovery.usable==='boolean')))
    this.goals=new Map(stored.value || [])
    if(stored.recovery) {
      for(const row of rows.values()) if(row.group && !this.goals.has(row.group)) {
        this.goals.set(row.group,{group:row.group,owner:row.goalCoordinator || row.owner,outcome:row.goalOutcome || row.group,
          criteria:row.goalCriteria || [],budget:{...GOAL_BUDGET},revision:1,memberIds:[],recovery:{usable:false}})
      }
      for(const goal of this.goals.values()) {
        goal.recoveryRequired=true
        goal.recovery={...stored.recovery,usable:stored.recovery.usable && goal.recovery?.usable!==false,
          preview:{outcome:goal.outcome,criteria:goal.criteria,budget:goal.budget}}
        goal.verification=null
      }
    }
    const unscoped=[...rows.values()].find(row=>row.recoveryRequired && !row.recovery.usable && !row.group)
    if(unscoped) for(const goal of this.goals.values()) if(!goal.memberIds) {
      goal.recoveryRequired=true
      goal.recovery={...unscoped.recovery,usable:false,note:'旧目标的成员记录存在损坏，请先核对工单或恢复完整备份。',
        preview:{outcome:goal.outcome,criteria:goal.criteria,budget:goal.budget}}
      goal.verification=null
    }
    this.committed=JSON.stringify([...this.goals])
    if(stored.recovery || unscoped || (stored.value && !stored.checkpointPresent)) this.commit()
  }
  commit() {
    try {
      this.committed=persistJSON(this.path,[...this.goals])
    } catch(error) {
      this.goals=new Map(JSON.parse(this.committed))
      throw error
    }
  }
  trackMembership(group) {
    const goal=this.goals.get(group)
    if(!goal) return
    const ids=[...new Set([...(goal.memberIds || []),...this.members(group).map(row=>row.id)])]
    if(JSON.stringify(ids)!==JSON.stringify(goal.memberIds)) {goal.memberIds=ids;this.commit()}
  }
  confirmRecovery(group,incident) {
    const goal=this.goals.get(group)
    if(!goal?.recoveryRequired || !goal.recovery.usable || goal.recovery.incidentId!==incident)
      throw new Error('目标恢复副本已变化或不可用，请重新核对')
    goal.recoveryRequired=false
    goal.recovery={...goal.recovery,confirmedAt:Date.now()}
    goal.verification=null
    this.commit()
  }
  ensure(group,owner) {
    if (!group) return null
    let goal=this.goals.get(group)
    if (!goal) {
      goal={ group,owner,outcome:group,criteria:[],revision:1,budget:{ ...GOAL_BUDGET },createdAt:Date.now(),memberIds:this.members(group).map(row=>row.id) }
      this.goals.set(group,goal); this.commit()
    }
    return goal
  }
  members(group) { return [...this.rows.values()].filter(row => row.group===group) }
  view(group) {
    const goal=this.goals.get(group)
    if (!goal) throw new Error('目标不存在')
    const rows=this.members(group)
    const fingerprint=hash([goal.revision,rows.map(row=>[row.id,row.revision,row.state,row.summary,row.authorizationRevoked])])
    const usage=rows.reduce((sum,row)=>{ const used=spent(row); for(const key of Object.keys(sum)) sum[key]+=used[key]; return sum },{ tokens:0,calls:0,milliseconds:0 })
    const missing=(goal.memberIds || []).some(id=>!this.rows.has(id))
    const ready=!missing && rows.length>0 && rows.every(row=>row.state==='completed' && !row.authorizationRevoked && !row.recoveryRequired)
    const verification=goal.verification?.fingerprint===fingerprint ? goal.verification : null
    return { ...goal,verification,fingerprint,usage,
      budgetExceeded:!!overBudget(usage,goal.budget),
      state:goal.recoveryRequired ? 'recovery' : verification?.passed ? 'verified' : ready ? 'verification' : overBudget(usage,goal.budget) ? 'budget' : 'working',
      plan:rows.map(row=>({ id:row.id,title:row.title,state:row.state,dependsOn:row.dependsOn||[],owner:row.owner })) }
  }
  define(group,owner,args) {
    if (!group?.trim() || group.length>120 || !args.outcome?.trim() || args.outcome.length>2000 ||
      !Array.isArray(args.criteria) || args.criteria.length<1 || args.criteria.length>12 || args.criteria.some(text=>typeof text!=='string'||!text.trim()||text.length>500))
      throw new Error('请给出目标、预期结果和 1 至 12 项可核验的完成条件')
    const previous=this.ensure(group,owner)
    if(previous.recoveryRequired) throw new Error('请在任务详情核对目标恢复副本并解锁')
    if (previous.owner!==owner) throw new Error('请在该目标的协调会话中调整完成条件')
    const next={ ...previous,outcome:args.outcome,criteria:[...new Set(args.criteria)],revision:previous.revision+1,
      budget:budget(args.budget,previous.budget),verification:null }
    this.goals.set(group,next); this.commit(); return this.view(group)
  }
  verify(group,owner,expected,checks) {
    const goal=this.view(group)
    if(goal.recoveryRequired) throw new Error('目标恢复副本尚待用户核对')
    if (goal.owner!==owner) throw new Error('请由该目标的协调会话核验结果')
    if (goal.fingerprint!==expected) throw new Error('目标状态已变化，请重新读取全部工单')
    if (goal.state!=='verification' || !goal.criteria.length) throw new Error('先完成各工单并定义完成条件，再核验目标')
    if (!Array.isArray(checks) || checks.length!==goal.criteria.length || checks.some((check,i)=>
      check.criterion!==goal.criteria[i] || typeof check.passed!=='boolean' || typeof check.evidence!=='string' ||
      !check.evidence.trim() || check.evidence.length>4000 || !goal.plan.some(row=>row.id===check.taskId)))
      throw new Error('逐项提供完成条件、工单 ID、核验结论和具体证据')
    const stored=this.goals.get(group)
    stored.verification={ fingerprint:expected,checks,passed:checks.every(check=>check.passed),verifier:owner,at:Date.now() }
    stored.verifications=(stored.verifications||[]).concat(stored.verification).slice(-20)
    this.commit(); return this.view(group)
  }
  denial(row,now=Date.now()) {
    if(row.recoveryRequired) return '工单恢复副本尚待用户核对。'
    if(row.group && this.goals.get(row.group)?.recoveryRequired) return '目标账本已从只读副本恢复，请先在任务详情核对并解锁。'

    if(overBudget(spent(row,now),row.budget||TASK_BUDGET)) return '本工单预算已用完，请确认进展并调整预算后继续原任务。'
    if(row.group && this.goals.has(row.group)) {
      const goal=this.view(row.group)
      if(overBudget(goal.usage,goal.budget)) return '该目标的总预算已用完，请在协调会话检查各工单后继续。'
    }
    return ''
  }
}
