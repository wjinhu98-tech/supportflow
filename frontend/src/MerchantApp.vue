<script setup lang="ts">
import { ref } from 'vue'
import { Store, ShieldCheck, LogOut, LoaderCircle, ArrowLeft } from '@lucide/vue'
import ReviewPanel from './components/ReviewPanel.vue'
import { useMerchantDesk } from './composables/useMerchantDesk'

const {
  username,
  connected,
  connecting,
  loginError,
  shops,
  shopId,
  selectedShop,
  tickets,
  historyTickets,
  view,
  loading,
  submitting,
  needsVerification,
  error,
  connect,
  disconnect,
  changeShop,
  loadReviews,
  submitReview,
  retryExecution,
} = useMerchantDesk()
const account = ref('')
const password = ref('')

async function login() {
  try {
    await connect(account.value.trim(), password.value)
  } finally {
    password.value = ''
  }
}

function logout() {
  if (disconnect()) {
    account.value = ''
    password.value = ''
  }
}

function switchShop(event: Event) {
  const select = event.target as HTMLSelectElement
  const requested = select.value
  // Keep the visible selection truthful even when a pending write prevents switching.
  select.value = shopId.value
  void changeShop(requested)
}
</script>

<template>
  <div class="merchant-page">
    <header class="merchant-header">
      <div class="merchant-brand">
        <Store :size="25" />
        <span>
          SupportFlow
          <strong>商家管理端</strong>
        </span>
      </div>
      <div class="merchant-header-actions">
        <a
          v-if="!submitting"
          href="/?shopId=100"
        >
          <ArrowLeft :size="16" />
          用户端
        </a>
        <template v-if="connected">
          <span>{{ username }}</span>
          <button
            type="button"
            data-testid="merchant-disconnect"
            :disabled="submitting"
            @click="logout"
          >
            <LogOut :size="16" />
            退出登录
          </button>
        </template>
      </div>
    </header>
    <main
      v-if="!connected"
      class="merchant-login-layout"
    >
      <section class="merchant-introduction">
        <p class="merchant-eyebrow">MERCHANT WORKSPACE</p>
        <h1>
          专注服务，
          <br />
          有序处理每一份售后。
        </h1>
        <p>
          查看获授权店铺的待审申请、记录审批依据，并跟进处理进度。买家咨询与商家管理使用独立入口。
        </p>
        <div>
          <ShieldCheck :size="21" />
          <span>店铺权限逐次核验 · 关键操作留痕 · 禁止申请人自审</span>
        </div>
      </section>
      <form
        class="merchant-login"
        data-testid="merchant-login"
        @submit.prevent="login"
      >
        <div class="merchant-login-icon"><Store :size="27" /></div>
        <h2>登录商家工作台</h2>
        <p>请使用已配置店铺授权的商家或客服账号。</p>
        <label for="merchant-username">员工账号</label>
        <input
          id="merchant-username"
          v-model="account"
          data-testid="merchant-username"
          autocomplete="username"
          required
          :disabled="connecting"
        />
        <label for="merchant-password">密码</label>
        <input
          id="merchant-password"
          v-model="password"
          data-testid="merchant-password"
          type="password"
          autocomplete="current-password"
          required
          :disabled="connecting"
        />
        <p
          v-if="loginError"
          role="alert"
          class="merchant-error"
        >
          {{ loginError }}
        </p>
        <button
          class="merchant-connect"
          data-testid="merchant-connect"
          type="submit"
          :disabled="connecting"
        >
          <LoaderCircle
            v-if="connecting"
            :size="17"
            class="merchant-spinning"
          />
          {{ connecting ? '正在验证身份…' : '登录管理端' }}
        </button>
        <small>密码仅在当前页面内存中用于连接，不写入浏览器本地存储。</small>
      </form>
    </main>
    <main
      v-else
      class="merchant-workspace"
      data-testid="merchant-workspace"
    >
      <section class="merchant-workspace-heading">
        <div>
          <p class="merchant-eyebrow">AFTER-SALES OPERATIONS</p>
          <h1>店铺售后管理</h1>
          <p>只处理当前账号已授权的店铺；批准后执行学习版模拟退款，不涉及真实资金。</p>
        </div>
        <label
          v-if="shops.length"
          class="merchant-shop-switch"
        >
          当前授权店铺
          <select
            data-testid="merchant-shop-select"
            :value="shopId"
            :disabled="submitting"
            @change="switchShop"
          >
            <option
              v-for="shop in shops"
              :key="shop.id"
              :value="String(shop.id)"
            >
              {{ shop.merchantName }} · {{ shop.name }}（{{ shop.id }}）
            </option>
          </select>
        </label>
      </section>
      <div
        v-if="!shops.length"
        class="merchant-empty"
        data-testid="merchant-no-shops"
      >
        <ShieldCheck :size="38" />
        <h2>尚未获得店铺授权</h2>
        <p>请联系管理员分配店铺。当前账号不能查看或处理任何店铺的售后申请。</p>
      </div>
      <p
        v-if="submitting"
        class="merchant-write-status"
        role="status"
      >
        正在提交操作，请等待结果后再切换店铺或退出。
      </p>
      <ReviewPanel
        v-if="selectedShop"
        :key="shopId"
        embedded
        :shop-name="selectedShop.name"
        :tickets="tickets"
        :history-tickets="historyTickets"
        :view="view"
        :loading="loading"
        :submitting="submitting"
        :write-blocked="needsVerification"
        :error="error"
        @refresh="loadReviews"
        @decide="submitReview"
        @retry="retryExecution"
      />
    </main>
  </div>
</template>

<style scoped>
.merchant-page {
  min-height: 100dvh;
  background: #f8f6fb;
  color: #42374f;
  font-family: inherit;
}
.merchant-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 21px max(24px, calc((100vw - 1280px) / 2));
  border-bottom: 1px solid #e9e2f0;
  background: #fff;
}
.merchant-brand {
  display: flex;
  gap: 12px;
  align-items: center;
  font-size: 19px;
  color: #6f53a4;
  text-decoration: none;
}
.merchant-brand strong {
  margin-left: 15px;
  color: #51435d;
  font-size: 15px;
  font-weight: 500;
}
.merchant-header-actions {
  display: flex;
  align-items: center;
  gap: 20px;
  font-size: 14px;
}
.merchant-header-actions a,
.merchant-header-actions button {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #795f96;
  text-decoration: none;
  background: transparent;
  border: 0;
  font: inherit;
  cursor: pointer;
}
.merchant-header-actions button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.merchant-login-layout {
  max-width: 1100px;
  margin: 0 auto;
  padding: 90px 25px;
  display: grid;
  grid-template-columns: 1.15fr 1fr;
  gap: 80px;
  align-items: center;
}
.merchant-eyebrow {
  font-size: 12px;
  letter-spacing: 1.6px;
  color: #8a74a2;
}
.merchant-introduction h1 {
  font-size: 37px;
  line-height: 1.65;
  font-weight: 600;
}
.merchant-introduction > p:not(.merchant-eyebrow) {
  line-height: 2;
  color: #7f708c;
  font-size: 15px;
  max-width: 440px;
}
.merchant-introduction > div {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: #8b72a0;
  margin-top: 30px;
}
.merchant-login {
  padding: 35px;
  border: 1px solid #e5dced;
  border-radius: 17px;
  background: #fff;
  box-shadow: 0 12px 40px #4d2f6c08;
}
.merchant-login-icon {
  display: grid;
  place-items: center;
  width: 56px;
  height: 56px;
  border-radius: 15px;
  color: #8161aa;
  background: #f0e9f9;
}
.merchant-login h2 {
  font-size: 23px;
  margin: 22px 0 10px;
}
.merchant-login > p {
  color: #8a7898;
  font-size: 14px;
  line-height: 1.8;
}
.merchant-login label {
  display: block;
  margin-top: 22px;
  margin-bottom: 9px;
  font-size: 14px;
  color: #6f577f;
}
.merchant-login input {
  box-sizing: border-box;
  width: 100%;
  min-height: 45px;
  border: 1px solid #dcd1e7;
  border-radius: 7px;
  padding: 11px;
  font: inherit;
  color: #51415f;
}
.merchant-login small {
  display: block;
  margin-top: 18px;
  color: #9a89a7;
  line-height: 1.8;
  font-size: 12px;
}
.merchant-connect {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  width: 100%;
  padding: 13px;
  margin-top: 26px;
  border: 0;
  border-radius: 7px;
  font: inherit;
  background: #7660bc;
  color: #fff;
  cursor: pointer;
}
.merchant-connect:disabled {
  opacity: 0.6;
  cursor: wait;
}
.merchant-workspace {
  max-width: 1200px;
  padding: 38px 24px 70px;
  margin: 0 auto;
}
.merchant-workspace-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 20px;
  margin-bottom: 26px;
}
.merchant-workspace-heading h1 {
  font-size: 28px;
  margin: 9px 0;
}
.merchant-workspace-heading p:not(.merchant-eyebrow) {
  font-size: 14px;
  color: #877593;
  line-height: 1.8;
}
.merchant-shop-switch {
  display: flex;
  flex-direction: column;
  gap: 9px;
  color: #836b95;
  font-size: 13px;
  max-width: 100%;
}
.merchant-shop-switch select {
  font: inherit;
  font-size: 14px;
  border: 1px solid #dacde6;
  border-radius: 8px;
  padding: 11px;
  color: #674b80;
  background: #fff;
  max-width: 100%;
}
.merchant-empty {
  padding: 70px 30px;
  text-align: center;
  background: #fff;
  border: 1px solid #e7ddef;
  border-radius: 15px;
  color: #8d73a5;
}
.merchant-empty h2 {
  font-weight: 500;
  font-size: 21px;
}
.merchant-empty p {
  font-size: 14px;
  line-height: 1.9;
}
.merchant-login .merchant-error,
.merchant-write-status {
  color: #9b5a4f;
  background: #fff6f1;
  border: 1px solid #eed4c8;
  border-radius: 8px;
  padding: 12px;
  line-height: 1.8;
  font-size: 14px;
}
.merchant-spinning {
  animation: merchant-spin 1s linear infinite;
}
@keyframes merchant-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 800px) {
  .merchant-login-layout {
    grid-template-columns: 1fr;
    gap: 30px;
    padding-top: 35px;
    max-width: 500px;
  }
  .merchant-introduction h1 {
    font-size: 28px;
  }
  .merchant-brand strong {
    display: block;
    margin-left: 0;
    margin-top: 4px;
  }
  .merchant-header {
    padding: 16px;
  }
  .merchant-header-actions {
    gap: 11px;
    flex-wrap: wrap;
    justify-content: flex-end;
  }
  .merchant-login {
    padding: 25px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .merchant-spinning {
    animation: none;
  }
}
</style>
