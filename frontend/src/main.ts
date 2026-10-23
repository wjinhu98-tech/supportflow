import { createApp } from 'vue'
import App from './App.vue'
import MerchantApp from './MerchantApp.vue'
import './styles.css'

const merchantEntry = /^\/merchant\/?$/.test(window.location.pathname)
createApp(merchantEntry ? MerchantApp : App).mount('#app')
