<template>
  <section class="login-page">
    <div class="login-mascots" aria-hidden="true">
      <MascotCanvas
        v-for="item in mascotItems"
        :key="item.seed"
        class="login-mascot"
        :style="{ left: item.left, top: item.top, animationDelay: item.delay, transform: `rotate(${item.rotate})` }"
        :size="item.size"
        :color="item.color"
        :seed="item.seed"
        :mood="error ? 'error' : 'happy'"
        :interactive="true"
        :look-away="passwordFocused"
      />
    </div>
    <div class="login-card" :class="{ 'shake-animation': Boolean(error), 'auth-submitting': submitting }">
      <div v-if="submitting" class="submit-overlay">{{ isRegisterMode ? '正在注册，请稍候...' : '正在登录，请稍候...' }}</div>
      <div class="auth-brand">
        <MascotCanvas class="brand-mascot" :size="60" color="#00b5ad" seed="login-brand" :mood="error ? 'error' : 'happy'" :interactive="true" />
        <div>
          <h1>{{ isRegisterMode ? '账号注册' : '账号登录' }}</h1>
          <p>{{ isRegisterMode ? '创建账号后继续管理你的测试资产' : '欢迎回来，继续管理你的测试资产' }}</p>
        </div>
      </div>

      <form v-if="!isRegisterMode" class="login-form" @submit.prevent="submitLogin">
        <label class="field">
          <span>用户名或邮箱 <b>*</b></span>
          <div class="input-with-icon">
            <span>👤</span>
            <input v-model.trim="loginForm.nameOrEmail" class="text-input" type="text" autocomplete="username" placeholder="请输入用户名或邮箱地址" />
          </div>
        </label>
        <label class="field">
          <span>密码 <b>*</b></span>
          <div class="password-row input-with-icon">
            <span>🔒</span>
            <input
              v-model="loginForm.password"
              class="text-input"
              :type="showLoginPassword ? 'text' : 'password'"
              autocomplete="current-password"
              placeholder="请输入密码"
              @focus="passwordFocused = true"
              @blur="passwordFocused = false"
            />
            <button
              class="password-toggle auth-password-toggle"
              type="button"
              :aria-label="showLoginPassword ? '隐藏密码' : '显示密码'"
              :title="showLoginPassword ? '隐藏密码' : '显示密码'"
              @click="showLoginPassword = !showLoginPassword"
            >
              <span :class="showLoginPassword ? 'eye-icon eye-open' : 'eye-icon eye-slash'"></span>
            </button>
          </div>
        </label>
        <p v-if="route.query.registered" class="success-text">注册成功，请登录。</p>
        <p v-if="route.query.error" class="error-text">{{ String(route.query.error) }}</p>
        <p v-if="error" class="error-text">{{ error }}</p>
        <button class="login-button" type="submit" :disabled="submitting">
          {{ submitting ? '登录中...' : '登录' }}
        </button>
        <div class="auth-link-row"><span>还没有账号？</span><RouterLink class="mode-link" to="/register">立即注册</RouterLink></div>
      </form>

      <form v-else class="login-form" @submit.prevent="submitRegister">
        <label class="field">
          <span>用户名 <b>*</b></span>
          <input v-model.trim="registerForm.name" class="text-input" type="text" autocomplete="username" pattern="^[A-Za-z0-9_]+$" placeholder="请输入用户名" />
        </label>
        <label class="field">
          <span>昵称</span>
          <input v-model.trim="registerForm.nickname" class="text-input" type="text" autocomplete="nickname" placeholder="请输入昵称" />
        </label>
        <label class="field">
          <span>邮箱 <b>*</b></span>
          <input v-model.trim="registerForm.email" class="text-input" type="email" autocomplete="email" placeholder="请输入邮箱地址" />
        </label>
        <label class="field">
          <span>密码</span>
          <div class="password-row">
            <input
              v-model="registerForm.password"
              class="text-input"
              :type="showRegisterPassword ? 'text' : 'password'"
              autocomplete="new-password"
            />
            <button
              class="password-toggle auth-password-toggle"
              type="button"
              :aria-label="showRegisterPassword ? '隐藏密码' : '显示密码'"
              :title="showRegisterPassword ? '隐藏密码' : '显示密码'"
              @click="showRegisterPassword = !showRegisterPassword"
            >
              <span :class="showRegisterPassword ? 'eye-icon eye-open' : 'eye-icon eye-slash'"></span>
            </button>
          </div>
        </label>
        <label class="field">
          <span>确认密码</span>
          <div class="password-row">
            <input
              v-model="registerForm.againPassword"
              class="text-input"
              :type="showRegisterConfirmPassword ? 'text' : 'password'"
              autocomplete="new-password"
            />
            <button
              class="password-toggle auth-password-toggle"
              type="button"
              :aria-label="showRegisterConfirmPassword ? '隐藏密码' : '显示密码'"
              :title="showRegisterConfirmPassword ? '隐藏密码' : '显示密码'"
              @click="showRegisterConfirmPassword = !showRegisterConfirmPassword"
            >
              <span :class="showRegisterConfirmPassword ? 'eye-icon eye-open' : 'eye-icon eye-slash'"></span>
            </button>
          </div>
        </label>
        <p v-if="error" class="error-text">{{ error }}</p>
        <button class="login-button" type="submit" :disabled="submitting">
          {{ submitting ? '注册中...' : '注册' }}
        </button>
        <RouterLink class="mode-link" to="/login">返回登录</RouterLink>
      </form>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import MascotCanvas from '@/components/MascotCanvas.vue'

import { ApiError } from '@/api/http'
import { register } from '@/api/bootstrap'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const submitting = ref(false)
const error = ref('')
const showLoginPassword = ref(false)
const showRegisterPassword = ref(false)
const showRegisterConfirmPassword = ref(false)
const passwordFocused = ref(false)
const loginForm = reactive({
  nameOrEmail: '',
  password: '',
})
const registerForm = reactive({
  name: '',
  nickname: '',
  email: '',
  password: '',
  againPassword: '',
})
const mascotItems = [
  { seed: 'login-a', left: '4%', top: '5%', size: 128, color: '#21ba45', delay: '0s', rotate: '-12deg' },
  { seed: 'login-b', left: '20%', top: '31%', size: 118, color: '#21ba45', delay: '-1.4s', rotate: '8deg' },
  { seed: 'login-c', left: '55%', top: '23%', size: 108, color: '#db2828', delay: '-.6s', rotate: '18deg' },
  { seed: 'login-d', left: '86%', top: '22%', size: 102, color: '#00b5cc', delay: '-2s', rotate: '-18deg' },
  { seed: 'login-e', left: '8%', top: '52%', size: 116, color: '#ff9a8a', delay: '-2.7s', rotate: '-8deg' },
  { seed: 'login-f', left: '64%', top: '55%', size: 122, color: '#21ba45', delay: '-3.2s', rotate: '5deg' },
  { seed: 'login-g', left: '86%', top: '49%', size: 126, color: '#00b5ad', delay: '-.9s', rotate: '28deg' },
  { seed: 'login-h', left: '80%', top: '78%', size: 128, color: '#a333c8', delay: '-4s', rotate: '-16deg' },
  { seed: 'login-i', left: '45%', top: '74%', size: 96, color: '#f2711c', delay: '-2.2s', rotate: '-6deg' },
  { seed: 'login-j', left: '70%', top: '6%', size: 82, color: '#2185d0', delay: '-1.1s', rotate: '12deg' },
  { seed: 'login-k', left: '34%', top: '9%', size: 78, color: '#fbbd08', delay: '-3.4s', rotate: '-22deg' },
  { seed: 'login-l', left: '91%', top: '66%', size: 80, color: '#e07b53', delay: '-1.9s', rotate: '15deg' },
]

const isRegisterMode = computed(() => route.name === 'register')

function resolveRedirect() {
  const redirect = route.query.redirect
  return normalizeRedirect(typeof redirect === 'string' ? redirect : '')
}

function normalizeRedirect(redirect: string) {
  let normalized = redirect.trim()
  for (let i = 0; i < 8; i += 1) {
    try {
      const decoded = decodeURIComponent(normalized)
      if (decoded === normalized) break
      normalized = decoded
    } catch {
      break
    }
  }

  if (!normalized || normalized.startsWith('http://') || normalized.startsWith('https://') || normalized.startsWith('//')) {
    return '/projects'
  }
  if (!normalized.startsWith('/') || normalized.startsWith('/login')) {
    return '/projects'
  }
  if (normalized.startsWith('/index.html')) {
    const queryIndex = normalized.indexOf('?')
    if (queryIndex >= 0) {
      const params = new URLSearchParams(normalized.slice(queryIndex + 1))
      const nested = params.get('redirect') || ''
      return normalizeRedirect(nested)
    }
    return '/projects'
  }
  if (normalized.includes('redirect=/index.html') || normalized.includes('redirect=%2Findex.html')) {
    return '/projects'
  }
  return normalized
}

async function submitLogin() {
  if (submitting.value) return
  if (!loginForm.nameOrEmail || !loginForm.password) {
    error.value = '请输入用户名和密码'
    return
  }

  submitting.value = true
  error.value = ''
  try {
    await authStore.login(loginForm.nameOrEmail, loginForm.password)
    await router.replace(resolveRedirect())
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : err instanceof Error ? err.message : '登录失败'
  } finally {
    submitting.value = false
  }
}

async function submitRegister() {
  if (submitting.value) return
  if (!registerForm.name || !registerForm.email || !registerForm.password || !registerForm.againPassword) {
    error.value = '请完整填写注册信息'
    return
  }
  if (!/^[A-Za-z0-9_]+$/.test(registerForm.name)) {
    error.value = '用户名只能包含数字、字母、下划线'
    return
  }
  if (registerForm.password.length < 6) {
    error.value = '密码至少需要 6 位'
    return
  }
  if (registerForm.password !== registerForm.againPassword) {
    error.value = '两次输入的密码不一致'
    return
  }

  submitting.value = true
  error.value = ''
  try {
    await register(registerForm)
    await router.replace('/login?registered=1')
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : err instanceof Error ? err.message : '注册失败'
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: calc(100vh - 120px);
  display: grid;
  place-items: center;
  position: relative;
  overflow: hidden;
  background:
    radial-gradient(circle at 14% 12%, rgba(var(--oat-primary-rgb), .13), transparent 32%),
    radial-gradient(circle at 82% 8%, rgba(var(--oat-accent-rgb), .10), transparent 30%),
    linear-gradient(110deg, rgba(225, 247, 248, .68), rgba(244, 248, 255, .88));
}

.login-mascots {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.login-mascot {
  position: absolute;
  opacity: .86;
  filter: drop-shadow(0 22px 28px rgba(15, 23, 42, .08));
  animation: mascot-drift 6.5s ease-in-out infinite alternate;
}

@keyframes mascot-drift {
  from { transform: translate3d(-8px, -6px, 0) rotate(-4deg); }
  to { transform: translate3d(10px, 8px, 0) rotate(5deg); }
}

.login-card {
  width: min(650px, calc(100vw - 32px));
  padding: 42px 44px 30px;
  border-radius: var(--oat-radius-2xl);
  border: 1px solid rgba(15, 23, 42, 0.08);
  background: rgba(255, 255, 255, 0.88);
  box-shadow: var(--oat-shadow-lg);
  backdrop-filter: saturate(180%) blur(18px);
  position: relative;
  z-index: 1;
}

.auth-brand {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 16px;
  margin-bottom: 26px;
  text-align: left;
}

.brand-mascot {
  flex: 0 0 auto;
}

.auth-brand h1 {
  margin: 0;
  color: var(--oat-text);
  font-size: clamp(30px, 4vw, 38px);
  line-height: 1.15;
  letter-spacing: -.03em;
}

.auth-brand p {
  margin: 8px 0 0;
  color: var(--oat-text-muted);
  font-size: 15px;
  font-weight: 700;
}

.submit-overlay {
  position: absolute;
  inset: 0;
  z-index: 3;
  display: grid;
  place-items: center;
  border-radius: inherit;
  background: rgba(255, 255, 255, .72);
  color: #0f766e;
  font-weight: 900;
  backdrop-filter: blur(5px);
}

.shake-animation {
  animation: auth-shake .34s ease;
}

@keyframes auth-shake {
  0%, 100% { transform: translateX(0); }
  20% { transform: translateX(-9px); }
  40% { transform: translateX(8px); }
  60% { transform: translateX(-5px); }
  80% { transform: translateX(4px); }
}

.login-form {
  display: grid;
  gap: 18px;
  margin-top: 8px;
}

.field {
  display: grid;
  gap: 8px;
}

.field span {
  font-size: 16px;
  color: #4b5563;
  font-weight: 800;
}

.field b {
  color: #e11d48;
}

.text-input {
  width: 100%;
  border: none;
  border-radius: 0;
  padding: 0;
  background: rgba(255, 255, 255, 0.96);
  outline: none;
  font-size: 16px;
}

.input-with-icon,
.password-row {
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 58px;
  border: 1px solid rgba(15, 23, 42, 0.10);
  border-radius: var(--oat-radius-lg);
  padding: 0 18px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: var(--oat-shadow-xs);
  transition: border-color .16s ease, box-shadow .16s ease, background .16s ease;
}

.input-with-icon:focus-within,
.password-row:focus-within {
  border-color: rgba(var(--oat-primary-rgb), .55);
  background: #fff;
  box-shadow: var(--oat-focus-ring);
}

.input-with-icon > span,
.password-row > span {
  flex: 0 0 auto;
  color: #a3aab4;
  font-size: 20px;
}

.password-row .text-input {
  flex: 1;
}

.password-toggle {
  flex: 0 0 auto;
  display: inline-grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: 999px;
  border: none;
  padding: 0;
  background: transparent;
  color: #9ca3af;
  cursor: pointer;
}

.eye-icon {
  position: relative;
  display: inline-block;
  width: 21px;
  height: 14px;
  border: 2px solid currentColor;
  border-radius: 60% 60% 55% 55%;
  transform: rotate(-2deg);
}

.eye-icon::before {
  content: '';
  position: absolute;
  left: 50%;
  top: 50%;
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: currentColor;
  transform: translate(-50%, -50%);
}

.eye-icon.eye-slash::after {
  content: '';
  position: absolute;
  left: -3px;
  top: 5px;
  width: 27px;
  height: 2px;
  border-radius: 999px;
  background: currentColor;
  transform: rotate(-36deg);
  box-shadow: 0 0 0 2px rgba(255, 255, 255, .92);
}

.password-toggle:hover {
  color: #4b5563;
}

.login-button {
  border: none;
  border-radius: 999px;
  padding: 18px 22px;
  margin-top: 8px;
  background: linear-gradient(135deg, var(--oat-primary), var(--oat-accent));
  color: #fff;
  font-size: 20px;
  font-weight: 900;
  cursor: pointer;
  box-shadow: 0 16px 36px rgba(var(--oat-primary-rgb), .24);
  transition: transform .16s ease, box-shadow .16s ease, opacity .16s ease;
}

.login-button:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 20px 44px rgba(var(--oat-primary-rgb), .28);
}

.login-button:active:not(:disabled) {
  transform: translateY(0) scale(.99);
}

.login-button:disabled {
  cursor: wait;
  opacity: 0.7;
}

.error-text,
.success-text {
  margin: 0;
}

.error-text {
  color: #b91c1c;
}

.success-text {
  color: #0f766e;
  font-weight: 700;
}

.mode-link {
  color: #0f766e;
  font-size: 15px;
  font-weight: 800;
}

.auth-link-row {
  display: flex;
  justify-content: center;
  gap: 8px;
  color: #94a3b8;
  font-size: 15px;
  font-weight: 700;
}

@media (max-width: 760px) {
  .login-card {
    padding: 28px 22px;
  }

  .auth-brand h1 {
    font-size: 28px;
  }

  .login-mascot {
    opacity: .34;
  }
}
</style>
