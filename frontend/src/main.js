import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'

// 必须排在 element-plus 的样式之后, 否则变量覆盖不生效
import './styles/theme.scss'

import { setupFocusRing } from './utils/focusRing'

import zhCn from 'element-plus/dist/locale/zh-cn.mjs'

const app = createApp(App)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
    app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, {
    locale: zhCn,
})

setupFocusRing()
app.mount('#app')
