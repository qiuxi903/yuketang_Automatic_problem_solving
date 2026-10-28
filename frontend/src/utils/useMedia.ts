import { onBeforeUnmount, ref, type Ref } from 'vue'

/**
 * 响应式媒体查询：返回是否命中（默认手机竖屏 ≤768px）。
 * 用于管理后台「桌面表格 / 手机卡片」两套视图的切换。
 */
export function useMediaQuery(query = '(max-width: 768px)'): Ref<boolean> {
  const matches = ref(false)
  if (typeof window === 'undefined' || !window.matchMedia) return matches

  const mql = window.matchMedia(query)
  matches.value = mql.matches
  const onChange = (e: MediaQueryListEvent) => (matches.value = e.matches)
  mql.addEventListener('change', onChange)
  onBeforeUnmount(() => mql.removeEventListener('change', onChange))
  return matches
}

/** 是否手机竖屏（≤768px）。 */
export function useIsMobile(): Ref<boolean> {
  return useMediaQuery('(max-width: 768px)')
}
