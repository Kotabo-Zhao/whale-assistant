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

const PIXEL_ICONS: Record<CuteIconName, string> = {
  appearance: 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%2216%22%20height=%2216%22%20viewBox=%220%200%2016%2016%22%20shape-rendering=%22crispEdges%22%3E%3Crect%20x=%223%22%20y=%222%22%20width=%2210%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%222%22%20y=%223%22%20width=%221%22%20height=%229%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%2213%22%20y=%223%22%20width=%221%22%20height=%226%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%223%22%20y=%2212%22%20width=%226%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%229%22%20y=%229%22%20width=%224%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%229%22%20y=%2210%22%20width=%221%22%20height=%222%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%223%22%20y=%223%22%20width=%2210%22%20height=%226%22%20fill=%22#fffdf0%22/%3E%3Crect%20x=%223%22%20y=%229%22%20width=%226%22%20height=%223%22%20fill=%22#fffdf0%22/%3E%3Crect%20x=%224%22%20y=%224%22%20width=%222%22%20height=%222%22%20fill=%22#ff5c93%22/%3E%3Crect%20x=%227%22%20y=%224%22%20width=%222%22%20height=%222%22%20fill=%22#ffd43b%22/%3E%3Crect%20x=%2210%22%20y=%224%22%20width=%222%22%20height=%222%22%20fill=%22#2cc76e%22/%3E%3Crect%20x=%224%22%20y=%227%22%20width=%222%22%20height=%222%22%20fill=%22#2d74f5%22/%3E%3Crect%20x=%227%22%20y=%227%22%20width=%222%22%20height=%222%22%20fill=%22#38e8ff%22/%3E%3C/svg%3E',
  assistant: 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%2216%22%20height=%2216%22%20viewBox=%220%200%2016%2016%22%20shape-rendering=%22crispEdges%22%3E%3Crect%20x=%227%22%20y=%221%22%20width=%222%22%20height=%222%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%226%22%20y=%223%22%20width=%224%22%20height=%222%22%20fill=%22#ffd43b%22/%3E%3Crect%20x=%222%22%20y=%226%22%20width=%2212%22%20height=%223%22%20fill=%22#ffd43b%22/%3E%3Crect%20x=%224%22%20y=%229%22%20width=%228%22%20height=%222%22%20fill=%22#ffd43b%22/%3E%3Crect%20x=%223%22%20y=%2211%22%20width=%223%22%20height=%223%22%20fill=%22#ffd43b%22/%3E%3Crect%20x=%2210%22%20y=%2211%22%20width=%223%22%20height=%223%22%20fill=%22#ffd43b%22/%3E%3Crect%20x=%227%22%20y=%224%22%20width=%222%22%20height=%224%22%20fill=%22#fffdf0%22/%3E%3Crect%20x=%226%22%20y=%227%22%20width=%224%22%20height=%221%22%20fill=%22#fffdf0%22/%3E%3C/svg%3E',
  chat: 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%2216%22%20height=%2216%22%20viewBox=%220%200%2016%2016%22%20shape-rendering=%22crispEdges%22%3E%3Crect%20x=%222%22%20y=%222%22%20width=%2212%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%221%22%20y=%223%22%20width=%221%22%20height=%228%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%2214%22%20y=%223%22%20width=%221%22%20height=%228%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%222%22%20y=%2211%22%20width=%223%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%227%22%20y=%2211%22%20width=%227%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%224%22%20y=%2212%22%20width=%221%22%20height=%222%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%225%22%20y=%2213%22%20width=%222%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%222%22%20y=%223%22%20width=%2212%22%20height=%228%22%20fill=%22#2d74f5%22/%3E%3Crect%20x=%225%22%20y=%2211%22%20width=%222%22%20height=%222%22%20fill=%22#2d74f5%22/%3E%3Crect%20x=%224%22%20y=%225%22%20width=%228%22%20height=%221%22%20fill=%22#fffdf0%22/%3E%3Crect%20x=%224%22%20y=%227%22%20width=%226%22%20height=%221%22%20fill=%22#fffdf0%22/%3E%3Crect%20x=%224%22%20y=%229%22%20width=%224%22%20height=%221%22%20fill=%22#38e8ff%22/%3E%3C/svg%3E',
  device: 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%2216%22%20height=%2216%22%20viewBox=%220%200%2016%2016%22%20shape-rendering=%22crispEdges%22%3E%3Crect%20x=%224%22%20y=%221%22%20width=%228%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%223%22%20y=%222%22%20width=%221%22%20height=%2212%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%2212%22%20y=%222%22%20width=%221%22%20height=%2212%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%224%22%20y=%2214%22%20width=%228%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%224%22%20y=%222%22%20width=%228%22%20height=%2212%22%20fill=%22#c8bc96%22/%3E%3Crect%20x=%225%22%20y=%223%22%20width=%226%22%20height=%226%22%20fill=%22#38e8ff%22/%3E%3Crect%20x=%225%22%20y=%2210%22%20width=%222%22%20height=%222%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%229%22%20y=%2210%22%20width=%221%22%20height=%221%22%20fill=%22#ff5c93%22/%3E%3Crect%20x=%2210%22%20y=%2211%22%20width=%221%22%20height=%221%22%20fill=%22#2cc76e%22/%3E%3C/svg%3E',
  history: 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%2216%22%20height=%2216%22%20viewBox=%220%200%2016%2016%22%20shape-rendering=%22crispEdges%22%3E%3Crect%20x=%225%22%20y=%221%22%20width=%226%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%223%22%20y=%222%22%20width=%222%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%2211%22%20y=%222%22%20width=%222%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%222%22%20y=%223%22%20width=%221%22%20height=%2210%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%2213%22%20y=%223%22%20width=%221%22%20height=%2210%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%223%22%20y=%2213%22%20width=%222%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%2211%22%20y=%2213%22%20width=%222%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%225%22%20y=%2214%22%20width=%226%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%223%22%20y=%223%22%20width=%2210%22%20height=%2210%22%20fill=%22#fffdf0%22/%3E%3Crect%20x=%225%22%20y=%222%22%20width=%226%22%20height=%221%22%20fill=%22#fffdf0%22/%3E%3Crect%20x=%225%22%20y=%2213%22%20width=%226%22%20height=%221%22%20fill=%22#fffdf0%22/%3E%3Crect%20x=%227%22%20y=%224%22%20width=%222%22%20height=%225%22%20fill=%22#2d74f5%22/%3E%3Crect%20x=%229%22%20y=%227%22%20width=%223%22%20height=%222%22%20fill=%22#ff5c93%22/%3E%3C/svg%3E',
  me: 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%2216%22%20height=%2216%22%20viewBox=%220%200%2016%2016%22%20shape-rendering=%22crispEdges%22%3E%3Crect%20x=%225%22%20y=%222%22%20width=%226%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%224%22%20y=%223%22%20width=%221%22%20height=%224%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%2211%22%20y=%223%22%20width=%221%22%20height=%224%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%225%22%20y=%223%22%20width=%226%22%20height=%224%22%20fill=%22#38e8ff%22/%3E%3Crect%20x=%226%22%20y=%224%22%20width=%221%22%20height=%222%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%229%22%20y=%224%22%20width=%221%22%20height=%222%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%225%22%20y=%227%22%20width=%226%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%223%22%20y=%229%22%20width=%2210%22%20height=%225%22%20fill=%22#2d74f5%22/%3E%3Crect%20x=%222%22%20y=%2210%22%20width=%221%22%20height=%224%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%2213%22%20y=%2210%22%20width=%221%22%20height=%224%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%223%22%20y=%2214%22%20width=%2210%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%226%22%20y=%2210%22%20width=%224%22%20height=%223%22%20fill=%22#fffdf0%22/%3E%3C/svg%3E',
  memory: 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%2216%22%20height=%2216%22%20viewBox=%220%200%2016%2016%22%20shape-rendering=%22crispEdges%22%3E%3Crect%20x=%223%22%20y=%221%22%20width=%2210%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%222%22%20y=%222%22%20width=%221%22%20height=%2212%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%2213%22%20y=%222%22%20width=%221%22%20height=%2212%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%223%22%20y=%2214%22%20width=%2210%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%223%22%20y=%222%22%20width=%2210%22%20height=%2212%22%20fill=%22#2d74f5%22/%3E%3Crect%20x=%223%22%20y=%222%22%20width=%222%22%20height=%2212%22%20fill=%22#1746a8%22/%3E%3Crect%20x=%226%22%20y=%224%22%20width=%225%22%20height=%222%22%20fill=%22#ffd43b%22/%3E%3Crect%20x=%226%22%20y=%227%22%20width=%225%22%20height=%221%22%20fill=%22#fffdf0%22/%3E%3Crect%20x=%226%22%20y=%229%22%20width=%224%22%20height=%221%22%20fill=%22#fffdf0%22/%3E%3C/svg%3E',
  mic: 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%2216%22%20height=%2216%22%20viewBox=%220%200%2016%2016%22%20shape-rendering=%22crispEdges%22%3E%3Crect%20x=%226%22%20y=%221%22%20width=%224%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%225%22%20y=%222%22%20width=%221%22%20height=%226%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%2210%22%20y=%222%22%20width=%221%22%20height=%226%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%226%22%20y=%222%22%20width=%224%22%20height=%226%22%20fill=%22#ff5c93%22/%3E%3Crect%20x=%227%22%20y=%223%22%20width=%222%22%20height=%222%22%20fill=%22#fffdf0%22/%3E%3Crect%20x=%226%22%20y=%228%22%20width=%224%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%223%22%20y=%226%22%20width=%221%22%20height=%224%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%2212%22%20y=%226%22%20width=%221%22%20height=%224%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%224%22%20y=%2210%22%20width=%228%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%227%22%20y=%2211%22%20width=%222%22%20height=%222%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%225%22%20y=%2213%22%20width=%226%22%20height=%222%22%20fill=%22#2d74f5%22/%3E%3C/svg%3E',
  models: 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%2216%22%20height=%2216%22%20viewBox=%220%200%2016%2016%22%20shape-rendering=%22crispEdges%22%3E%3Crect%20x=%224%22%20y=%223%22%20width=%228%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%223%22%20y=%224%22%20width=%221%22%20height=%228%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%2212%22%20y=%224%22%20width=%221%22%20height=%228%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%224%22%20y=%2212%22%20width=%228%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%224%22%20y=%224%22%20width=%228%22%20height=%228%22%20fill=%22#2d74f5%22/%3E%3Crect%20x=%226%22%20y=%226%22%20width=%224%22%20height=%224%22%20fill=%22#38e8ff%22/%3E%3Crect%20x=%225%22%20y=%221%22%20width=%222%22%20height=%222%22%20fill=%22#ffd43b%22/%3E%3Crect%20x=%229%22%20y=%221%22%20width=%222%22%20height=%222%22%20fill=%22#ffd43b%22/%3E%3Crect%20x=%225%22%20y=%2213%22%20width=%222%22%20height=%222%22%20fill=%22#ffd43b%22/%3E%3Crect%20x=%229%22%20y=%2213%22%20width=%222%22%20height=%222%22%20fill=%22#ffd43b%22/%3E%3Crect%20x=%221%22%20y=%225%22%20width=%222%22%20height=%222%22%20fill=%22#ffd43b%22/%3E%3Crect%20x=%221%22%20y=%229%22%20width=%222%22%20height=%222%22%20fill=%22#ffd43b%22/%3E%3Crect%20x=%2213%22%20y=%225%22%20width=%222%22%20height=%222%22%20fill=%22#ffd43b%22/%3E%3Crect%20x=%2213%22%20y=%229%22%20width=%222%22%20height=%222%22%20fill=%22#ffd43b%22/%3E%3C/svg%3E',
  'new-chat': 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%2216%22%20height=%2216%22%20viewBox=%220%200%2016%2016%22%20shape-rendering=%22crispEdges%22%3E%3Crect%20x=%222%22%20y=%222%22%20width=%2210%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%221%22%20y=%223%22%20width=%221%22%20height=%228%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%2212%22%20y=%223%22%20width=%221%22%20height=%225%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%222%22%20y=%2211%22%20width=%226%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%222%22%20y=%223%22%20width=%2210%22%20height=%228%22%20fill=%22#2d74f5%22/%3E%3Crect%20x=%224%22%20y=%225%22%20width=%226%22%20height=%221%22%20fill=%22#fffdf0%22/%3E%3Crect%20x=%224%22%20y=%227%22%20width=%224%22%20height=%221%22%20fill=%22#fffdf0%22/%3E%3Crect%20x=%2211%22%20y=%228%22%20width=%222%22%20height=%226%22%20fill=%22#2cc76e%22/%3E%3Crect%20x=%229%22%20y=%2210%22%20width=%226%22%20height=%222%22%20fill=%22#2cc76e%22/%3E%3C/svg%3E',
  notifications: 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%2216%22%20height=%2216%22%20viewBox=%220%200%2016%2016%22%20shape-rendering=%22crispEdges%22%3E%3Crect%20x=%227%22%20y=%221%22%20width=%222%22%20height=%222%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%225%22%20y=%223%22%20width=%226%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%224%22%20y=%224%22%20width=%228%22%20height=%226%22%20fill=%22#ffd43b%22/%3E%3Crect%20x=%223%22%20y=%224%22%20width=%221%22%20height=%226%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%2212%22%20y=%224%22%20width=%221%22%20height=%226%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%222%22%20y=%2210%22%20width=%2212%22%20height=%222%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%223%22%20y=%2210%22%20width=%2210%22%20height=%221%22%20fill=%22#ffd43b%22/%3E%3Crect%20x=%226%22%20y=%2212%22%20width=%224%22%20height=%222%22%20fill=%22#ff5c93%22/%3E%3Crect%20x=%225%22%20y=%225%22%20width=%222%22%20height=%223%22%20fill=%22#fffdf0%22/%3E%3C/svg%3E',
  send: 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%2216%22%20height=%2216%22%20viewBox=%220%200%2016%2016%22%20shape-rendering=%22crispEdges%22%3E%3Crect%20x=%222%22%20y=%222%22%20width=%223%22%20height=%222%22%20fill=%22#2cc76e%22/%3E%3Crect%20x=%222%22%20y=%224%22%20width=%227%22%20height=%222%22%20fill=%22#2cc76e%22/%3E%3Crect%20x=%222%22%20y=%226%22%20width=%2212%22%20height=%223%22%20fill=%22#2cc76e%22/%3E%3Crect%20x=%222%22%20y=%229%22%20width=%227%22%20height=%222%22%20fill=%22#2cc76e%22/%3E%3Crect%20x=%222%22%20y=%2211%22%20width=%223%22%20height=%222%22%20fill=%22#2cc76e%22/%3E%3Crect%20x=%224%22%20y=%227%22%20width=%226%22%20height=%221%22%20fill=%22#fffdf0%22/%3E%3C/svg%3E',
  slash: 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%2216%22%20height=%2216%22%20viewBox=%220%200%2016%2016%22%20shape-rendering=%22crispEdges%22%3E%3Crect%20x=%222%22%20y=%222%22%20width=%2212%22%20height=%2212%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%223%22%20y=%223%22%20width=%2210%22%20height=%2210%22%20fill=%22#1a1238%22/%3E%3Crect%20x=%224%22%20y=%225%22%20width=%222%22%20height=%222%22%20fill=%22#2cc76e%22/%3E%3Crect%20x=%226%22%20y=%227%22%20width=%222%22%20height=%222%22%20fill=%22#2cc76e%22/%3E%3Crect%20x=%224%22%20y=%229%22%20width=%222%22%20height=%222%22%20fill=%22#2cc76e%22/%3E%3Crect%20x=%229%22%20y=%229%22%20width=%223%22%20height=%222%22%20fill=%22#ffd43b%22/%3E%3C/svg%3E',
  tasks: 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%2216%22%20height=%2216%22%20viewBox=%220%200%2016%2016%22%20shape-rendering=%22crispEdges%22%3E%3Crect%20x=%223%22%20y=%221%22%20width=%2210%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%222%22%20y=%222%22%20width=%221%22%20height=%2212%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%2213%22%20y=%222%22%20width=%221%22%20height=%2212%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%223%22%20y=%2214%22%20width=%2210%22%20height=%221%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%223%22%20y=%222%22%20width=%2210%22%20height=%2212%22%20fill=%22#fffdf0%22/%3E%3Crect%20x=%224%22%20y=%224%22%20width=%222%22%20height=%222%22%20fill=%22#2cc76e%22/%3E%3Crect%20x=%227%22%20y=%224%22%20width=%225%22%20height=%222%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%224%22%20y=%227%22%20width=%222%22%20height=%222%22%20fill=%22#ffd43b%22/%3E%3Crect%20x=%227%22%20y=%227%22%20width=%225%22%20height=%222%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%224%22%20y=%2210%22%20width=%222%22%20height=%222%22%20fill=%22#ff5c93%22/%3E%3Crect%20x=%227%22%20y=%2210%22%20width=%224%22%20height=%222%22%20fill=%22#1d1829%22/%3E%3C/svg%3E',
  'voice-off': 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%2216%22%20height=%2216%22%20viewBox=%220%200%2016%2016%22%20shape-rendering=%22crispEdges%22%3E%3Crect%20x=%222%22%20y=%226%22%20width=%223%22%20height=%224%22%20fill=%22#c8bc96%22/%3E%3Crect%20x=%225%22%20y=%224%22%20width=%223%22%20height=%228%22%20fill=%22#c8bc96%22/%3E%3Crect%20x=%2210%22%20y=%225%22%20width=%222%22%20height=%222%22%20fill=%22#ff5c93%22/%3E%3Crect%20x=%2212%22%20y=%227%22%20width=%222%22%20height=%222%22%20fill=%22#ff5c93%22/%3E%3Crect%20x=%2210%22%20y=%229%22%20width=%222%22%20height=%222%22%20fill=%22#ff5c93%22/%3E%3Crect%20x=%2212%22%20y=%225%22%20width=%222%22%20height=%222%22%20fill=%22#ff5c93%22/%3E%3Crect%20x=%2210%22%20y=%227%22%20width=%222%22%20height=%222%22%20fill=%22#ff5c93%22/%3E%3Crect%20x=%2212%22%20y=%229%22%20width=%222%22%20height=%222%22%20fill=%22#ff5c93%22/%3E%3C/svg%3E',
  'voice-on': 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%2216%22%20height=%2216%22%20viewBox=%220%200%2016%2016%22%20shape-rendering=%22crispEdges%22%3E%3Crect%20x=%222%22%20y=%226%22%20width=%223%22%20height=%224%22%20fill=%22#2d74f5%22/%3E%3Crect%20x=%225%22%20y=%224%22%20width=%223%22%20height=%228%22%20fill=%22#2d74f5%22/%3E%3Crect%20x=%229%22%20y=%226%22%20width=%222%22%20height=%224%22%20fill=%22#38e8ff%22/%3E%3Crect%20x=%2212%22%20y=%224%22%20width=%222%22%20height=%228%22%20fill=%22#2cc76e%22/%3E%3C/svg%3E',
  workshop: 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%2216%22%20height=%2216%22%20viewBox=%220%200%2016%2016%22%20shape-rendering=%22crispEdges%22%3E%3Crect%20x=%229%22%20y=%222%22%20width=%224%22%20height=%224%22%20fill=%22#ff5c93%22/%3E%3Crect%20x=%227%22%20y=%225%22%20width=%223%22%20height=%223%22%20fill=%22#2d74f5%22/%3E%3Crect%20x=%225%22%20y=%227%22%20width=%223%22%20height=%223%22%20fill=%22#2d74f5%22/%3E%3Crect%20x=%223%22%20y=%229%22%20width=%223%22%20height=%224%22%20fill=%22#ffd43b%22/%3E%3Crect%20x=%2210%22%20y=%223%22%20width=%222%22%20height=%222%22%20fill=%22#fffdf0%22/%3E%3C/svg%3E',
  workspace: 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%2216%22%20height=%2216%22%20viewBox=%220%200%2016%2016%22%20shape-rendering=%22crispEdges%22%3E%3Crect%20x=%222%22%20y=%223%22%20width=%225%22%20height=%222%22%20fill=%22#ffd43b%22/%3E%3Crect%20x=%221%22%20y=%225%22%20width=%2214%22%20height=%229%22%20fill=%22#1d1829%22/%3E%3Crect%20x=%222%22%20y=%226%22%20width=%2212%22%20height=%227%22%20fill=%22#ffd43b%22/%3E%3Crect%20x=%223%22%20y=%227%22%20width=%2210%22%20height=%222%22%20fill=%22#fffdf0%22/%3E%3C/svg%3E',
}

export function CuteIcon({ name, size = 20, className = '' }: { name: CuteIconName; size?: number; className?: string }) {
  const style = useInterfaceStyle()
  const src = style === 'pixel' ? PIXEL_ICONS[name] : (style === 'usagi' ? USAGI_ICONS[name] : CUTE_ICONS[name])
  const baseClass = style === 'pixel' ? 'whale-cute-icon is-pixel-sprite' : 'whale-cute-icon'
  return (
    <img
      src={src}
      alt=""
      width={size}
      height={size}
      aria-hidden="true"
      className={className ? `${baseClass} ${className}` : baseClass}
    />
  )
}
