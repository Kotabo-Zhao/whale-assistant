import appearanceIcon from '../assets/cute-icons/appearance.webp'
import assistantIcon from '../assets/cute-icons/assistant.webp'
import chatIcon from '../assets/cute-icons/chat.webp'
import deviceIcon from '../assets/cute-icons/device.webp'
import historyIcon from '../assets/cute-icons/history.webp'
import meIcon from '../assets/cute-icons/me.webp'
import memoryIcon from '../assets/cute-icons/memory.webp'
import micIcon from '../assets/cute-icons/mic.webp'
import modelsIcon from '../assets/cute-icons/models.webp'
import newChatIcon from '../assets/cute-icons/new-chat.webp'
import notificationsIcon from '../assets/cute-icons/notifications.webp'
import sendIcon from '../assets/cute-icons/send.webp'
import slashIcon from '../assets/cute-icons/slash.webp'
import tasksIcon from '../assets/cute-icons/tasks.webp'
import voiceOffIcon from '../assets/cute-icons/voice-off.webp'
import voiceOnIcon from '../assets/cute-icons/voice-on.webp'
import workshopIcon from '../assets/cute-icons/workshop.webp'
import workspaceIcon from '../assets/cute-icons/workspace.webp'

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
