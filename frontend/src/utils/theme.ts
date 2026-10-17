import { ref } from 'vue'

/** 亮/暗主题：localStorage 记忆，html.dark 类驱动（Element Plus 暗色变量同步生效）。 */

const THEME_KEY = 'ykt_theme'

export type Theme = 'light' | 'dark'

const theme = ref<Theme>('light')

function apply(next: Theme) {
  theme.value = next
  document.documentElement.classList.toggle('dark', next === 'dark')
  localStorage.setItem(THEME_KEY, next)
}

export function initTheme() {
  const saved = localStorage.getItem(THEME_KEY)
  apply(saved === 'dark' ? 'dark' : 'light')
}

export function toggleTheme() {
  apply(theme.value === 'dark' ? 'light' : 'dark')
}

export function useTheme() {
  return theme
}
