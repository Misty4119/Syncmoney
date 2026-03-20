import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import i18n from './i18n'

import './styles/main.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(i18n)


if (import.meta.env.DEV) {
  async function startMSW() {
    try {
      const { worker } = await import('../mocks/browser')
      return worker.start({
        onUnhandledRequest: 'bypass'
      })
    } catch (e) {
      console.warn('MSW not available:', e)
    }
  }
  startMSW().then(() => {
    app.mount('#app')
  })
} else {
  app.mount('#app')
}
