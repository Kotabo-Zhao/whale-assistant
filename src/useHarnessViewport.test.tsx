import { act, cleanup, render } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const state=vi.hoisted(()=>({ native:true,write:vi.fn() }))
vi.mock('@capacitor/core',()=>({Capacitor:{isNativePlatform:()=>state.native}}))
vi.mock('./platform/native',()=>({runtimeBridge:{setHarnessViewport:state.write}}))
import { useHarnessViewport } from './useHarnessViewport'

let frames:Map<number,FrameRequestCallback>,next:number,resize:ResizeObserverCallback,disconnected:number
let rect={x:0,y:64,width:420,height:670}
function Frame({view='harness',covered=false}:{view?:string;covered?:boolean}) {
  useHarnessViewport(view,covered)
  return <><main className="app-main" ref={node=>{if(node)node.getBoundingClientRect=()=>({...rect,toJSON:()=>({}),top:rect.y,left:rect.x,right:rect.x+rect.width,bottom:rect.y+rect.height})}}/><nav data-testid="shared-tabs">同一套标签</nav></>
}
async function paint(){for(let i=0;i<2;i++)await act(async()=>{const batch=[...frames];frames.clear();for(const [,fn]of batch)fn(i*16);await Promise.resolve()})}
beforeEach(()=>{
  state.native=true;state.write.mockReset().mockResolvedValue(undefined)
  frames=new Map();next=0;disconnected=0;rect={x:0,y:64,width:420,height:670}
  vi.stubGlobal('requestAnimationFrame',(fn:FrameRequestCallback)=>{frames.set(++next,fn);return next})
  vi.stubGlobal('cancelAnimationFrame',(id:number)=>frames.delete(id))
  vi.stubGlobal('ResizeObserver',class {constructor(fn:ResizeObserverCallback){resize=fn}observe(){}disconnect(){disconnected++}})
})
afterEach(()=>{cleanup();vi.unstubAllGlobals()})

it('mounts conversation only inside the measured content, retaining the same navigation DOM',async()=>{
  const root=render(<Frame/>),tabs=root.getByTestId('shared-tabs')
  await paint()
  expect(state.write).toHaveBeenLastCalledWith({...rect,view:'harness',visible:true,viewportWidth:window.innerWidth})
  root.rerender(<Frame view="settings"/>)
  expect(root.getByTestId('shared-tabs')).toBe(tabs)
  await paint()
  expect(state.write).toHaveBeenLastCalledWith(expect.objectContaining({view:'settings',visible:false}))
})
it('drops stale layout callbacks when rapidly switching tabs and disconnects on unmount',async()=>{
  const root=render(<Frame/>);root.rerender(<Frame view="tools"/>);root.rerender(<Frame view="assistant-memory"/>)
  await paint()
  expect(state.write).toHaveBeenCalledTimes(1)
  expect(state.write).toHaveBeenCalledWith(expect.objectContaining({view:'assistant-memory',visible:false}))
  act(()=>resize([],{} as ResizeObserver));root.unmount();await paint()
  expect(state.write).toHaveBeenCalledTimes(1);expect(disconnected).toBe(3)
})
it('reflows for viewport changes and hides content for an App dialog',async()=>{
  const root=render(<Frame/>);await paint()
  rect={x:70,y:64,width:700,height:320}
  act(()=>resize([],{} as ResizeObserver));await paint()
  expect(state.write).toHaveBeenLastCalledWith(expect.objectContaining({...rect,visible:true}))
  root.rerender(<Frame covered/>);await paint()
  expect(state.write).toHaveBeenLastCalledWith(expect.objectContaining({view:'harness',visible:false}))
})
it('does not create a native surface in a browser',async()=>{
  state.native=false;render(<Frame/>);await paint();expect(state.write).not.toHaveBeenCalled()
})
