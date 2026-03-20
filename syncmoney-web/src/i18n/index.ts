import { createI18n } from 'vue-i18n'
import zhTW from './locales/zh-TW.json'
import enUS from './locales/en-US.json'

export type MessageSchema = typeof zhTW

const i18n = createI18n<[MessageSchema], 'zh-TW' | 'en-US'>({
  legacy: false,
  locale: localStorage.getItem('locale') || 'zh-TW',
  fallbackLocale: 'zh-TW',
  messages: {
    'zh-TW': zhTW,
    'en-US': enUS
  }
})

export default i18n
