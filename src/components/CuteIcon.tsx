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

import usagiAppearanceIcon from '../assets/usagi-icons/appearance.webp'
import usagiAssistantIcon from '../assets/usagi-icons/assistant.webp'
import usagiChatIcon from '../assets/usagi-icons/chat.webp'
import usagiDeviceIcon from '../assets/usagi-icons/device.webp'
import usagiHistoryIcon from '../assets/usagi-icons/history.webp'
import usagiMeIcon from '../assets/usagi-icons/me.webp'
import usagiMemoryIcon from '../assets/usagi-icons/memory.webp'
import usagiMicIcon from '../assets/usagi-icons/mic.webp'
import usagiModelsIcon from '../assets/usagi-icons/models.webp'
import usagiNewChatIcon from '../assets/usagi-icons/new-chat.webp'
import usagiNotificationsIcon from '../assets/usagi-icons/notifications.webp'
import usagiSendIcon from '../assets/usagi-icons/send.webp'
import usagiSlashIcon from '../assets/usagi-icons/slash.webp'
import usagiTasksIcon from '../assets/usagi-icons/tasks.webp'
import usagiVoiceOffIcon from '../assets/usagi-icons/voice-off.webp'
import usagiVoiceOnIcon from '../assets/usagi-icons/voice-on.webp'
import usagiWorkshopIcon from '../assets/usagi-icons/workshop.webp'
import usagiWorkspaceIcon from '../assets/usagi-icons/workspace.webp'
import { useInterfaceStyle } from '../theme'

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

const USAGI_ICONS: Record<CuteIconName, string> = {
  appearance: usagiAppearanceIcon,
  assistant: usagiAssistantIcon,
  chat: usagiChatIcon,
  device: usagiDeviceIcon,
  history: usagiHistoryIcon,
  me: usagiMeIcon,
  memory: usagiMemoryIcon,
  mic: usagiMicIcon,
  models: usagiModelsIcon,
  'new-chat': usagiNewChatIcon,
  notifications: usagiNotificationsIcon,
  send: usagiSendIcon,
  slash: usagiSlashIcon,
  tasks: usagiTasksIcon,
  'voice-off': usagiVoiceOffIcon,
  'voice-on': usagiVoiceOnIcon,
  workshop: usagiWorkshopIcon,
  workspace: usagiWorkspaceIcon,
}

export function CuteIcon({ name, size = 20, className = '' }: { name: CuteIconName; size?: number; className?: string }) {
  const style = useInterfaceStyle()
  const src = style === 'usagi' ? USAGI_ICONS[name] : CUTE_ICONS[name]
  return (
    <img
      src={src}
      alt=""
      width={size}
      height={size}
      aria-hidden="true"
      className={className ? `whale-cute-icon ${className}` : 'whale-cute-icon'}
    />
  )
}
