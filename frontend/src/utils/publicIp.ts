/**
 * 浏览器侧公网出口 IP 探测。
 *
 * 用户设备直连公网探测服务拿真实上网 IP（请求不经过平台隧道，内网穿透也拿得到），
 * 存 localStorage 后随每个 API 请求带上（X-Client-Public-IP），供后端在
 * 「隧道没透传真实 IP」时作为来源展示。可伪造，仅展示用；限流/安全判定不用它。
 */

const KEY = 'ykt_public_ip'
let cached = localStorage.getItem(KEY) ?? ''

/** 已探测到的公网 IP（可能为空串 = 还没探测到）。 */
export function publicIp(): string {
  return cached
}

// 探测端点按可达性排序：国内 ipip 优先（CORS *），国际 ip.sb / ipify 兜底
const endpoints: Array<[string, (j: any) => string | undefined]> = [
  ['https://myip.ipip.net/json', (j) => j?.data?.ip],
  ['https://api.ip.sb/jsonip', (j) => j?.ip],
  ['https://api.ipify.org?format=json', (j) => j?.ip],
]

/** 探测一次公网 IP；已缓存则直接返回。全部端点不可达时返回空串（下次页面打开再试）。 */
export async function detectPublicIp(): Promise<string> {
  if (cached) return cached
  for (const [url, pick] of endpoints) {
    try {
      const ctl = new AbortController()
      const timer = setTimeout(() => ctl.abort(), 4000)
      const res = await fetch(url, { signal: ctl.signal })
      clearTimeout(timer)
      if (!res.ok) continue
      const ip = pick(await res.json())
      if (ip) {
        cached = ip
        localStorage.setItem(KEY, ip)
        return cached
      }
    } catch {
      // 该探测服务不可达 → 试下一个
    }
  }
  return cached
}
