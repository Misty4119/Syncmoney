import { authHandlers } from './auth'
import { auditHandlers } from './audit'
import { systemHandlers } from './system'
import { settingsHandlers } from './settings'
import { configHandlers } from './config'

export const handlers = [
  ...authHandlers,
  ...auditHandlers,
  ...systemHandlers,
  ...settingsHandlers,
  ...configHandlers
]
