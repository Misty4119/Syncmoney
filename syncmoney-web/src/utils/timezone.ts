
const timezoneAliases: Record<string, string> = {
  'UTC': 'UTC',
  'UTC+8': 'Asia/Shanghai',
  'UTC+7': 'Asia/Bangkok',
  'UTC+9': 'Asia/Tokyo',
  'UTC+1': 'Europe/Paris',
  'UTC+2': 'Europe/Berlin',
  'UTC-5': 'America/New_York',
  'UTC-8': 'America/Los_Angeles',
  '+8': 'Asia/Shanghai',
  '+7': 'Asia/Bangkok',
  '+9': 'Asia/Tokyo',
}

export function normalizeTimezone(timezone: string): string {

  if (timezone.includes('/')) {
    return timezone
  }


  if (timezoneAliases[timezone]) {
    return timezoneAliases[timezone]
  }


  const match = timezone.match(/^([+-]?\d+)$/)
  if (match) {
    const offset = parseInt(match[1])
    const hours = Math.floor(Math.abs(offset))
    const sign = offset >= 0 ? '+' : '-'
    return `Etc/GMT${sign}${hours}`
  }


  return 'UTC'
}
