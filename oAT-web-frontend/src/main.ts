import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import { installOatTooltip } from './shared/tooltip'
import './style.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
installOatTooltip(app)
app.mount('#app')
