import { apiClient } from '@/api/client'

export type ConfigFieldType = 'string' | 'number' | 'boolean' | 'array' | 'object'

export interface ConfigField {
  value: unknown
  type: ConfigFieldType
  editable: boolean
  min?: number
  max?: number
  allowedValues?: string[]
}

export interface ConfigSection {
  [key: string]: ConfigField | ConfigSection
}

export interface PluginConfig {
  redis: {
    enabled: boolean
    host: string
    port: string
    password: string
    editable: boolean
  }
  database: {
    type: string
    host: string
    port: string
    database: string
    editable: boolean
  }
  core: ConfigSection
  economy: ConfigSection
  display: ConfigSection
  pay: ConfigSection
  baltop: ConfigSection
  'circuit-breaker': ConfigSection
  'discord-webhook': ConfigSection
  audit: ConfigSection
  'admin-permissions': ConfigSection
  messages: ConfigSection
  'cross-server-notifications': ConfigSection
  'shadow-sync': ConfigSection
  'web-admin.ui': ConfigSection
}

export interface ConfigChange {
  section: string
  key: string
  value: unknown
}

export interface ValidationResult {
  valid: boolean
  message: string
}

export interface SaveConfigResponse {
  message: string
  hotReloaded: boolean
}

export const configService = {
  /**
   * [SYNC-WEB-006] Get full configuration with editable markers
   */
  async getConfig(): Promise<PluginConfig> {
    const response = await apiClient.get<{ success: boolean; data: PluginConfig }>('/api/config')
    if (!response.data.success) {
      throw new Error('Failed to load config')
    }
    return response.data.data
  },

  /**
   * [SYNC-WEB-007] Save configuration changes
   */
  async saveConfig(changes: ConfigChange[], hotReload = true): Promise<SaveConfigResponse> {
    const response = await apiClient.put<{ success: boolean; data: SaveConfigResponse; error?: { message: string } }>('/api/config', {
      changes,
      hotReload
    })
    if (!response.data.success) {
      throw new Error(response.data.error?.message || 'Failed to save config')
    }
    return response.data.data
  },

  /**
   * [SYNC-WEB-008] Validate a configuration value
   */
  async validate(section: string, key: string, value: unknown): Promise<ValidationResult> {
    const response = await apiClient.post<{ success: boolean; data: ValidationResult; error?: { message: string } }>('/api/config/validate', {
      section,
      key,
      value
    })
    if (!response.data.success) {
      return { valid: false, message: response.data.error?.message || 'Validation failed' }
    }
    return response.data.data
  },

  /**
   * [SYNC-WEB-009] Hot reload configuration
   */
  async reloadConfig(): Promise<void> {
    const response = await apiClient.post<{ success: boolean }>('/api/config/reload')
    if (!response.data.success) {
      throw new Error('Failed to reload config')
    }
  }
}

/**
 * [SYNC-WEB-010] Flatten nested config section for easier rendering
 */
export function flattenConfigSection(section: ConfigSection, prefix = ''): Array<{
  key: string
  fullKey: string
  value: unknown
  type: ConfigFieldType
  editable: boolean
  min?: number
  max?: number
  allowedValues?: string[]
}> {
  const result: Array<{
    key: string
    fullKey: string
    value: unknown
    type: ConfigFieldType
    editable: boolean
    min?: number
    max?: number
    allowedValues?: string[]
  }> = []

  for (const [key, value] of Object.entries(section)) {

    const fullKey = prefix ? `${prefix}.${key}` : key


    if (value && typeof value === 'object' && !Array.isArray(value)) {
      const fieldObj = value as Record<string, unknown>


      if ('value' in fieldObj && 'editable' in fieldObj && 'type' in fieldObj) {

        const field = value as ConfigField
        result.push({
          key,
          fullKey,
          value: field.value,
          type: field.type,
          editable: field.editable,
          min: field.min,
          max: field.max,
          allowedValues: field.allowedValues
        })
      } else {

        result.push(...flattenConfigSection(value as ConfigSection, fullKey))
      }
    }
  }

  return result
}

/**
 * [SYNC-WEB-011] Group config fields by section for display
 */
export function groupConfigBySection(config: PluginConfig): Array<{
  key: string
  title: string
  fields: Array<{
    key: string
    fullKey: string
    value: unknown
    type: ConfigFieldType
    editable: boolean
    min?: number
    max?: number
    allowedValues?: string[]
  }>
}> {
  const sections: Array<{
    key: string
    title: string
    fields: Array<{
      key: string
      fullKey: string
      value: unknown
      type: ConfigFieldType
      editable: boolean
      min?: number
      max?: number
      allowedValues?: string[]
    }>
  }> = []

  const skipKeys = ['redis', 'database']

  for (const [sectionKey, sectionValue] of Object.entries(config)) {
    if (skipKeys.includes(sectionKey)) continue
    if (!sectionValue || typeof sectionValue !== 'object') continue

    const fields = flattenConfigSection(sectionValue as ConfigSection)

    sections.push({
      key: sectionKey,
      title: sectionKey,
      fields
    })
  }

  return sections
}
