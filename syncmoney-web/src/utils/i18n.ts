
import { useI18n } from 'vue-i18n'

export function useI18nHelper() {
  const { t } = useI18n()
  return { t }
}
