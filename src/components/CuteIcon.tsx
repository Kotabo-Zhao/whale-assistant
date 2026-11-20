import appearanceIcon from '../assets/cute-icons/appearance.png'
import assistantIcon from '../assets/cute-icons/assistant.png'
import chatIcon from '../assets/cute-icons/chat.png'
import deviceIcon from '../assets/cute-icons/device.png'
import historyIcon from '../assets/cute-icons/history.png'
import meIcon from '../assets/cute-icons/me.png'
import memoryIcon from '../assets/cute-icons/memory.png'
import micIcon from '../assets/cute-icons/mic.png'
import modelsIcon from '../assets/cute-icons/models.png'
import newChatIcon from '../assets/cute-icons/new-chat.png'
import notificationsIcon from '../assets/cute-icons/notifications.png'
import sendIcon from '../assets/cute-icons/send.png'
import slashIcon from '../assets/cute-icons/slash.png'
import tasksIcon from '../assets/cute-icons/tasks.png'
import voiceOffIcon from '../assets/cute-icons/voice-off.png'
import voiceOnIcon from '../assets/cute-icons/voice-on.png'
import workshopIcon from '../assets/cute-icons/workshop.png'
import workspaceIcon from '../assets/cute-icons/workspace.png'

export type CuteIconName =
  | 'appearance'
  | 'assistant'
  | 'chat'
  | 'device'
  | 'history'
  | 'me'
  | 'memory'
  | 'mic'
  | 'models'
  | 'new-chat'
  | 'notifications'
  | 'send'
  | 'slash'
  | 'tasks'
  | 'voice-off'
  | 'voice-on'
  | 'workshop'
  | 'workspace'

const CUTE_ICONS: Record<CuteIconName, string> = {
  appearance: appearanceIcon,
  assistant: assistantIcon,
  chat: chatIcon,
  device: deviceIcon,
  history: historyIcon,
  me: meIcon,
  memory: memoryIcon,
  mic: micIcon,
  models: modelsIcon,
  'new-chat': newChatIcon,
  notifications: notificationsIcon,
  send: sendIcon,
  slash: slashIcon,
  tasks: tasksIcon,
  'voice-off': voiceOffIcon,
  'voice-on': voiceOnIcon,
  workshop: workshopIcon,
  workspace: workspaceIcon,
}

export function CuteIcon({ name, size = 20, className = '' }: { name: CuteIconName; size?: number; className?: string }) {
  return (
    <img
      src={CUTE_ICONS[name]}
      alt=""
      width={size}
      height={size}
      aria-hidden="true"
      className={className ? `whale-cute-icon ${className}` : 'whale-cute-icon'}
    />
  )
}
