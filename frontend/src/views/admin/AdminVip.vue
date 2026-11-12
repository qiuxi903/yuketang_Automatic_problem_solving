<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CopyDocument, Delete, Refresh } from '@element-plus/icons-vue'
import { listUsers, listAuditLogs } from '@/api/admin'
import type { AdminUserView } from '@/types'
import {
  batchVipCoupons,
  confirmVipOrder,
  deleteVipActivity,
  disableVipCoupon,
  enableVipCoupon,
  generateVipCoupons,
  getPayQrImages,
  getVipProducts,
  getVipSettings,
  getVipStats,
  grantVip,
  deleteBatchPrice,
  listBatchPrices,
  setBatchPrice,
  exportOrdersCsv,
  exportPayNotificationsCsv,
  expireStaleOrders,
  listVipActivities,
  listVipBindings,
  listVipCoupons,
  listVipEntitlements,
  deletePayNotification,
  listPayNotifications,
  listVipOrders,
  matchPayText,
  rejectVipOrder,
  revokeVipEntitlement,
  saveVipActivity,
  unbindYkt,
  updateVipSettings,
  type VipActivityView,
  type VipBindingView,
  type VipCouponView,
  type VipEntitlementView,
  type VipOrder,
  type VipOrderStatus,
  type VipSettingsView,
  type VipStats,
  type PayNotificationView,
} from '@/api/vip'
import { formatDateTime } from '@/utils/format'
import { useIsMobile } from '@/utils/useMedia'

/** VIP 运营：订单核销（监听/人工）、优惠码、活动与支付设置、用户权益与雨课堂绑定。 */

const isMobile = useIsMobile()
const activeTab = ref<'orders' | 'coupons' | 'pay' | 'users'>('orders')

const fen2yuan = (fen: number) => (fen / 100).toFixed(2)

/* ================= 订单 ================= */

const orders = ref<VipOrder[]>([])
const ordersLoading = ref(false)
const orderFilter = ref<'ALL' | VipOrderStatus>('ALL')
const matchRaw = ref('')
const matching = ref(false)
const stats = ref<VipStats | null>(null)
const notifications = ref<PayNotificationView[]>([])

const fen2yuanStats = (fen: number) => (fen / 100).toFixed(2)

const conversionPercent = computed(() => {
  if (!stats.value || !stats.value.totalUsers) return 0
  return Math.round((stats.value.paidUsers / stats.value.totalUsers) * 100)
})

const channelText = computed(() => {
  const co = stats.value?.channelOrders ?? {}
  const map: Record<string, string> = { LISTEN: '监听', MANUAL: '人工', XUNHU: '虎皮椒' }
  const keys = Object.keys(co)
  if (!keys.length) return '—'
  return keys.map((k) => `${map[k] ?? k} ${co[k]}`).join(' / ')
})

/** 近 7 日柱图高度百分比（最大值满高，全 0 时给出 4% 的底柱） */
const dailyBars = computed(() => {
  const daily = stats.value?.dailyRevenue ?? []
  const max = Math.max(1, ...daily.map((d) => d.revenueFen))
  return daily.map((d) => ({
    date: d.date.slice(5), // MM-dd
    revenueFen: d.revenueFen,
    orders: d.orders,
    height: d.revenueFen === 0 ? 4 : Math.max(6, Math.round((d.revenueFen / max) * 100)),
  }))
})

const filteredOrders = computed(() =>
  orders.value.filter((o) => orderFilter.value === 'ALL' || o.status === orderFilter.value),
)
const pendingCount = computed(() => orders.value.filter((o) => o.status === 'CREATED').length)

const orderStatusMeta: Record<VipOrderStatus, { label: string; tag: 'success' | 'info' | 'warning' | 'danger' }> = {
  CREATED: { label: '待支付', tag: 'warning' },
  PAID: { label: '已支付', tag: 'success' },
  CANCELLED: { label: '已取消', tag: 'info' },
  REJECTED: { label: '已拒单', tag: 'danger' },
  EXPIRED: { label: '已过期', tag: 'info' },
}

function kindLabel(order: VipOrder) {
  return order.kind === 'DATE_PASS' ? `单日 · ${order.targetBatch}` : '月卡'
}

/** 模板槽位的 row 是 any，统一走类型化辅助函数取映射，避免 noImplicitAny 报错。 */
function orderMetaOf(row: VipOrder) {
  return orderStatusMeta[row.status]
}
function channelLabel(channel: string) {
  return ({ LISTEN: '监听', MANUAL: '人工', XUNHU: '虎皮椒' } as Record<string, string>)[channel] ?? channel
}
function couponKindLabel(kind: string) {
  return (kindLabelMap as Record<string, string>)[kind] ?? kind
}
function appliesLabel(v: string) {
  return ({ DATE: '单日卡', MONTH: '月卡', ALL: '全部' } as Record<string, string>)[v] ?? v
}

async function loadOrders() {
  ordersLoading.value = true
  try {
    const [list, st, notes] = await Promise.all([listVipOrders(), getVipStats(), listPayNotifications()])
    orders.value = list
    stats.value = st
    notifications.value = notes
  } finally {
    ordersLoading.value = false
  }
}

async function confirm(order: VipOrder) {
  let remark: string | undefined
  try {
    const { value } = await ElMessageBox.prompt(
      `确认已收到「${kindLabel(order)}」订单 ¥${fen2yuan(order.payableFen)} 的付款？确认后权益立即生效。`,
      '确认到账',
      { confirmButtonText: '确认到账', cancelButtonText: '取消', inputPlaceholder: '备注（可选，如转账人/流水号）' },
    )
    remark = value || undefined
  } catch {
    return
  }
  try {
    await confirmVipOrder(order.orderNo, remark)
    ElMessage.success(`订单 ${order.orderNo} 已确认，权益已发放`)
    await loadOrders()
  } catch {
    /* 拦截器已提示 */
  }
}

async function reject(order: VipOrder) {
  let reason: string | undefined
  try {
    const { value } = await ElMessageBox.prompt('拒单后用户需重新下单，请在备注写明原因。', '拒单', {
      confirmButtonText: '拒单',
      cancelButtonText: '取消',
      type: 'warning',
      inputPlaceholder: '拒单原因',
    })
    reason = value || undefined
  } catch {
    return
  }
  try {
    await rejectVipOrder(order.orderNo, reason)
    ElMessage.success('已拒单')
    await loadOrders()
  } catch {
    /* 拦截器已提示 */
  }
}

const channelLabelNote = (c: string) =>
  ({ wechat: '微信', alipay: '支付宝', 'manual-paste': '粘贴', xunhu: '虎皮椒' } as Record<string, string>)[c] ?? c

async function removeNotification(row: PayNotificationView) {
  try {
    await ElMessageBox.confirm(
      `删除这笔到账记录（¥${fen2yuan(row.amountFen)}，${formatDateTime(row.receivedAt)}）？删除后无法再被申报核实匹配。`,
      '删除到账记录', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await deletePayNotification(row.id)
  ElMessage.success('已删除')
  await loadOrders()
}

/** 粘贴收款通知文本（如「微信支付收款3.47元」）按金额快速核销。 */
async function onExpireStale() {
  try {
    const res = await expireStaleOrders()
    ElMessage.success(`已清理 ${res.expired} 个过期单`)
    await loadOrders()
  } catch {
    /* 拦截器已提示 */
  }
}

async function onExportOrders() {
  await exportOrdersCsv()
  ElMessage.success('订单流水已下载')
}

async function onExportNotes() {
  await exportPayNotificationsCsv()
  ElMessage.success('收款流水已下载')
}

async function matchText() {
  if (!matchRaw.value.trim()) {
    ElMessage.warning('请粘贴收款通知文本')
    return
  }
  matching.value = true
  try {
    const res = await matchPayText(matchRaw.value)
    if (res.matched) {
      ElMessage.success('已匹配到订单并核销')
      matchRaw.value = ''
      await loadOrders()
    } else {
      ElMessage.warning('金额未匹配到待支付订单（可能已过期或已核销），请人工核对')
    }
  } catch {
    /* 拦截器已提示 */
  } finally {
    matching.value = false
  }
}

/* ================= 优惠码 ================= */

const coupons = ref<VipCouponView[]>([])
const couponsLoading = ref(false)
const couponSelected = ref<string[]>([])

const genVisible = ref(false)
const generating = ref(false)
const genForm = ref({
  count: 5,
  kind: 'DATE_PASS' as VipCouponView['kind'],
  valuePercent: 80,
  quotaCount: 1,
  appliesTo: 'ALL' as 'DATE' | 'MONTH' | 'ALL',
  maxUses: 1,
  unlimited: false,
  perUserLimit: 1,
  expiresDays: 30,
})

const kindLabelMap: Record<VipCouponView['kind'], string> = {
  DISCOUNT: '折扣码',
  DATE_PASS: '单日兑换码',
  MONTH_PASS: '月卡兑换码',
  QUOTA: '额度码',
}

async function loadCoupons() {
  couponsLoading.value = true
  try {
    coupons.value = await listVipCoupons()
  } finally {
    couponsLoading.value = false
  }
}

function onCouponSelect(rows: VipCouponView[]) {
  couponSelected.value = rows.map((r) => r.code)
}

async function generateCoupons() {
  generating.value = true
  try {
    const f = genForm.value
    const res = await generateVipCoupons({
      count: f.count,
      kind: f.kind,
      valuePercent: f.kind === 'DISCOUNT' ? f.valuePercent : undefined,
      quotaCount: f.kind === 'QUOTA' ? f.quotaCount : undefined,
      appliesTo: f.kind === 'DISCOUNT' ? f.appliesTo : undefined,
      maxUses: f.unlimited ? 0 : Math.max(1, f.maxUses),
      perUserLimit: Math.max(1, f.perUserLimit),
      expiresDays: f.expiresDays > 0 ? f.expiresDays : undefined,
    })
    ElMessage.success(`已生成 ${res.codes.length} 个${kindLabelMap[f.kind]}`)
    genVisible.value = false
    await loadCoupons()
    await copyText(res.codes.join('\n'), '兑换码已复制到剪贴板，可直接发给用户/发卡平台')
  } catch {
    /* 拦截器已提示 */
  } finally {
    generating.value = false
  }
}

async function toggleCoupon(row: VipCouponView) {
  if (row.disabled) await enableVipCoupon(row.code)
  else await disableVipCoupon(row.code)
  ElMessage.success(row.disabled ? '已启用' : '已禁用')
  await loadCoupons()
}

async function couponBatch(action: 'disable' | 'enable' | 'delete') {
  const codes = [...couponSelected.value]
  if (!codes.length) return
  if (action === 'delete') {
    try {
      await ElMessageBox.confirm(`确定删除所选 ${codes.length} 个优惠码？`, '批量删除', {
        type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }
  const res = await batchVipCoupons(action, codes)
  ElMessage.success(`已处理 ${res.affected} 个`)
  couponSelected.value = []
  await loadCoupons()
}

async function copyText(text: string, tip: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(tip)
  } catch {
    ElMessage.warning('当前环境不支持自动复制，请手动复制')
  }
}

/* ================= 活动与支付设置 ================= */

const settings = ref<VipSettingsView | null>(null)
const paySaving = ref(false)
const qrWechat = ref<string | null>(null)
const qrAlipay = ref<string | null>(null)
const settingsForm = ref({
  datePassYuan: 3,
  monthYuan: 29,
  monthDays: 30,
  payChannel: 'LISTEN' as VipSettingsView['payChannel'],
  payNote: '',
})

const activities = ref<VipActivityView[]>([])
const batchRows = ref<{ batch: string; papersCount: number; priceYuan: number; isCustom: boolean }[]>([])
const batchSaving = ref('')
const actVisible = ref(false)
const actSaving = ref(false)
const actForm = ref({
  name: '',
  discountPercent: 80,
  appliesTo: 'ALL' as 'DATE' | 'MONTH' | 'ALL',
  range: [] as string[],
})

async function loadPay() {
  const [s, acts, qr, products, prices] = await Promise.all([
    getVipSettings(), listVipActivities(), getPayQrImages(), getVipProducts(), listBatchPrices(),
  ])
  settings.value = s
  activities.value = acts
  qrWechat.value = qr.wechat
  qrAlipay.value = qr.alipay
  const overrideMap = new Map(prices.map((o) => [o.batch, o.priceFen]))
  batchRows.value = products.batches.map((b) => ({
    batch: b.batch,
    papersCount: b.papersCount,
    priceYuan: (overrideMap.get(b.batch) ?? b.priceFen) / 100,
    isCustom: overrideMap.has(b.batch),
  }))
  settingsForm.value = {
    datePassYuan: s.datePassPriceFen / 100,
    monthYuan: s.monthPriceFen / 100,
    monthDays: s.monthDays,
    payChannel: s.payChannel,
    payNote: s.payNote ?? '',
  }
}

async function saveSettings() {
  paySaving.value = true
  try {
    await updateVipSettings({
      datePassPriceFen: Math.round(settingsForm.value.datePassYuan * 100),
      monthPriceFen: Math.round(settingsForm.value.monthYuan * 100),
      monthDays: Math.max(1, settingsForm.value.monthDays),
      payChannel: settingsForm.value.payChannel,
      payNote: settingsForm.value.payNote,
    })
    ElMessage.success('支付设置已保存')
    await loadPay()
  } catch {
    /* 拦截器已提示 */
  } finally {
    paySaving.value = false
  }
}

function onQrFile(kind: 'wechat' | 'alipay', file: File) {
  if (file.size > 1024 * 1024) {
    ElMessage.warning('图片请控制在 1MB 内')
    return
  }
  const reader = new FileReader()
  reader.onload = () => {
    const dataUrl = String(reader.result)
    if (kind === 'wechat') qrWechat.value = dataUrl
    else qrAlipay.value = dataUrl
    void saveQr()
  }
  reader.readAsDataURL(file)
}

/** el-upload on-change 适配：取原始 File 交给 onQrFile。 */
function onQrUpload(kind: 'wechat' | 'alipay') {
  return (uploadFile: unknown) => {
    const raw = (uploadFile as { raw?: File }).raw
    if (raw) onQrFile(kind, raw)
  }
}

async function saveQr() {
  try {
    await updateVipSettings({
      payQrWechat: qrWechat.value ?? '',
      payQrAlipay: qrAlipay.value ?? '',
    })
    ElMessage.success('收款码已更新')
  } catch {
    /* 拦截器已提示 */
  }
}

async function clearQr(kind: 'wechat' | 'alipay') {
  if (kind === 'wechat') qrWechat.value = null
  else qrAlipay.value = null
  await saveQr()
}

/** 保存某日期的单独定价（元 → 分）。 */
async function saveBatchPrice(row: { batch: string; priceYuan: number }) {
  batchSaving.value = row.batch
  try {
    await setBatchPrice(row.batch, Math.round(row.priceYuan * 100))
    ElMessage.success(`日期 ${row.batch} 的解锁价已设为 ¥${row.priceYuan}`)
    await loadPay()
  } catch {
    /* 拦截器已提示 */
  } finally {
    batchSaving.value = ''
  }
}

/** 恢复某日期为全局默认价。 */
async function resetBatchPrice(batch: string) {
  batchSaving.value = batch
  try {
    await deleteBatchPrice(batch)
    ElMessage.success(`日期 ${batch} 已恢复默认价`)
    await loadPay()
  } catch {
    /* 拦截器已提示 */
  } finally {
    batchSaving.value = ''
  }
}

async function addActivity() {
  actSaving.value = true
  try {
    const f = actForm.value
    await saveVipActivity({
      name: f.name,
      discountPercent: f.discountPercent,
      appliesTo: f.appliesTo,
      startsAt: f.range[0] ? new Date(f.range[0]).toISOString() : null,
      endsAt: f.range[1] ? new Date(f.range[1]).toISOString() : null,
      enabled: true,
    })
    ElMessage.success('活动已创建，窗口期内下单自动生效')
    actVisible.value = false
    actForm.value = { name: '', discountPercent: 80, appliesTo: 'ALL', range: [] }
    await loadPay()
  } catch {
    /* 拦截器已提示 */
  } finally {
    actSaving.value = false
  }
}

async function removeActivity(id: number) {
  try {
    await ElMessageBox.confirm('确定删除该活动？删除后窗口期折扣立即失效。', '删除活动', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  await deleteVipActivity(id)
  ElMessage.success('已删除')
  await loadPay()
}

/* ================= 用户权益与绑定 ================= */

const users = ref<AdminUserView[]>([])
const entitlements = ref<VipEntitlementView[]>([])
const bindings = ref<VipBindingView[]>([])
const batches = ref<string[]>([])
const usersLoading = ref(false)
const auditLogs = ref<import('@/api/admin').AuditLogView[]>([])
const grantForm = ref({
  scope: 'one' as 'one' | 'all',
  userId: null as number | null,
  kind: 'MONTH' as 'MONTH' | 'DATE' | 'QUOTA',
  days: 30,
  targetBatch: '',
  quota: 1,
})

async function loadUsers() {
  usersLoading.value = true
  try {
    const [u, e, b, p, audits] = await Promise.allSettled([
      listUsers(),
      listVipEntitlements(),
      listVipBindings(),
      getVipProducts(),
      listAuditLogs(100),
    ])
    if (u.status === 'fulfilled') users.value = u.value
    if (e.status === 'fulfilled') entitlements.value = e.value
    if (b.status === 'fulfilled') bindings.value = b.value
    if (p.status === 'fulfilled') batches.value = p.value.batches.map((x) => x.batch)
    if (audits.status === 'fulfilled') auditLogs.value = audits.value
  } finally {
    usersLoading.value = false
  }
}

async function doGrant() {
  const f = grantForm.value
  const payload = {
    kind: f.kind,
    days: f.kind === 'MONTH' ? f.days : undefined,
    targetBatch: f.kind === 'DATE' ? f.targetBatch : undefined,
    quota: f.kind === 'QUOTA' ? f.quota : undefined,
  }

  // 批量模式：向全部普通用户发放同一权益（正加负减同样适用），逐人容错
  if (f.scope === 'all') {
    if (f.kind === 'QUOTA' && (f.quota ?? 0) === 0) {
      ElMessage.warning('额度变化为 0，没有实际效果')
      return
    }
    try {
      await ElMessageBox.confirm(
        '将向全部普通用户（不含管理员）发放该权益，确认执行？',
        '批量发放',
        { confirmButtonText: '确认发放', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
    const res = await grantAllUsers(payload)
    ElMessage.success(res.message || '批量发放完成')
    await loadUsers()
    return
  }

  if (!f.userId) {
    ElMessage.warning('请选择用户')
    return
  }
  if (f.kind === 'DATE' && !f.targetBatch) {
    ElMessage.warning('请选择要解锁的日期')
    return
  }
  try {
    const res = await grantVip(f.userId, payload)
    ElMessage.success(res.message || '已发放')
    await loadUsers()
  } catch {
    /* 拦截器已提示 */
  }
}

async function revoke(row: VipEntitlementView) {
  try {
    await ElMessageBox.confirm(
      `撤销用户「${row.username}」的${row.kind === 'MONTH' ? '月卡' : `日期 ${row.targetBatch}`}权益？撤销后立即失效。`,
      '撤销权益', { type: 'warning', confirmButtonText: '撤销', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await revokeVipEntitlement(row.id)
  ElMessage.success('已撤销')
  await loadUsers()
}

async function unbind(row: VipBindingView) {
  try {
    await ElMessageBox.confirm(
      `解绑用户「${row.username}」的雨课堂账号「${row.yktName}」？解绑后该用户下次扫码将重新绑定，该雨课堂账号也可被其他用户绑定。`,
      '解绑雨课堂账号', { type: 'warning', confirmButtonText: '解绑', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await unbindYkt(row.userId)
  ElMessage.success('已解绑')
  await loadUsers()
}

/* ================= 汇总加载 ================= */

async function loadTab(tab: typeof activeTab.value) {
  if (tab === 'orders') await loadOrders()
  else if (tab === 'coupons') await loadCoupons()
  else if (tab === 'pay') await loadPay()
  else await loadUsers()
}

onMounted(() => void loadTab(activeTab.value))
</script>

<template>
  <div class="admin-vip">
    <div class="head">
      <div>
        <h2>VIP 运营</h2>
        <p>订单核销、优惠码与活动、支付渠道设置、用户权益与雨课堂绑定，一页搞定。</p>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="vip-tabs" @tab-change="loadTab">
      <!-- ================= 订单管理 ================= -->
      <el-tab-pane label="订单管理" name="orders" lazy>
        <!-- 统计条：收入 / 订单 / 会员 / 额度 / 优惠码 -->
        <div v-if="stats" class="vip-stats">
          <div class="vs-cell">
            <span class="vs-label">累计收入</span>
            <strong class="vs-gold">¥{{ fen2yuanStats(stats.totalRevenueFen) }}</strong>
            <em>今日 ¥{{ fen2yuanStats(stats.todayRevenueFen) }}</em>
          </div>
          <div class="vs-cell">
            <span class="vs-label">订单</span>
            <strong>已付 {{ stats.paidOrders }}</strong>
            <em>待支付 <b :class="{ 'vs-warn': stats.pendingOrders > 0 }">{{ stats.pendingOrders }}</b> · 拒单 {{ stats.rejectedOrders }}</em>
          </div>
          <div class="vs-cell">
            <span class="vs-label">
              付费用户
              <el-tooltip content="有已支付订单的去重用户数；转化率 = 付费用户 ÷ 注册用户" placement="top">
                <span class="vs-q">?</span>
              </el-tooltip>
            </span>
            <strong>{{ stats.paidUsers }} 人</strong>
            <em>转化率 {{ conversionPercent }}% · 注册 {{ stats.totalUsers }} 人</em>
          </div>
          <div class="vs-cell">
            <span class="vs-label">渠道分布</span>
            <strong class="vs-channel">{{ channelText }}</strong>
            <em>已支付订单按渠道</em>
          </div>
          <div class="vs-cell">
            <span class="vs-label">月卡在期</span>
            <strong>{{ stats.monthActiveUsers }} 人</strong>
            <em>单日解锁 {{ stats.dateEntitlements }} 人次</em>
          </div>
          <div class="vs-cell">
            <span class="vs-label">免费额度</span>
            <strong>{{ stats.freeQuotaTotal }} 次</strong>
            <em>全体用户剩余合计</em>
          </div>
          <div class="vs-cell">
            <span class="vs-label">优惠码</span>
            <strong>可用 {{ stats.couponsActive }}</strong>
            <em>累计核销 {{ stats.couponsUsed }} 次</em>
          </div>
          <div class="vs-cell vs-chart">
            <span class="vs-label">近 7 日收入</span>
            <div class="vs-bars">
              <div v-for="b in dailyBars" :key="b.date" class="vs-bar-col">
                <div class="vs-bar" :style="{ height: b.height + '%' }" />
                <span class="vs-bar-date">{{ b.date }}</span>
              </div>
            </div>
          </div>
        </div>
        <!-- 最近到账记录：监听收到什么一目了然，未核销的可被用户申报核实匹配 -->
        <div v-if="notifications.length" class="notes-card">
          <div class="notes-head">
            <span class="vs-label">最近到账记录（{{ notifications.length }} 条，含未核销）</span>
            <el-button size="small" :icon="Refresh" @click="loadOrders">刷新</el-button>
          </div>
          <el-table :data="notifications" size="small" max-height="180">
            <el-table-column label="时间" width="150">
              <template #default="{ row }">{{ formatDateTime(row.receivedAt) }}</template>
            </el-table-column>
            <el-table-column label="渠道" width="80">
              <template #default="{ row }">{{ channelLabelNote(row.channel) }}</template>
            </el-table-column>
            <el-table-column label="金额" width="90">
              <template #default="{ row }">
                <span class="payable mono-sm">¥{{ fen2yuan(row.amountFen) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="150">
              <template #default="{ row }">
                <el-tag v-if="row.consumed" type="success" effect="light" size="small">
                  已核销 {{ row.consumedByOrderNo }}
                </el-tag>
                <el-tag v-else type="warning" effect="light" size="small">待匹配（可被申报核实）</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="raw" label="通知原文" min-width="180" show-overflow-tooltip />
            <el-table-column label="操作" width="70" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="danger" plain @click="removeNotification(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="toolbar">
          <span class="stats">
            待支付 <b class="warn">{{ pendingCount }}</b>
            <el-divider direction="vertical" />
            共 {{ orders.length }} 单
          </span>
          <div class="toolbar-actions">
            <el-button size="small" @click="onExpireStale">清理过期单</el-button>
            <el-button size="small" @click="onExportOrders">导出订单 CSV</el-button>
            <el-button size="small" @click="onExportNotes">导出收款 CSV</el-button>
          </div>
          <el-radio-group v-model="orderFilter" size="small">
            <el-radio-button value="ALL">全部</el-radio-button>
            <el-radio-button value="CREATED">待支付</el-radio-button>
            <el-radio-button value="PAID">已支付</el-radio-button>
            <el-radio-button value="CANCELLED">已取消</el-radio-button>
            <el-radio-button value="REJECTED">已拒单</el-radio-button>
            <el-radio-button value="EXPIRED">已过期</el-radio-button>
          </el-radio-group>
        </div>

        <!-- 粘贴收款文本快速核销 -->
        <div class="match-box">
          <el-input
            v-model="matchRaw"
            placeholder="监听漏单兜底：粘贴收款通知文本，如「微信支付收款3.47元」，按金额自动核销"
            clearable
          />
          <el-button type="warning" :loading="matching" @click="matchText">快速核销</el-button>
        </div>

        <el-table v-if="!isMobile" :data="filteredOrders" v-loading="ordersLoading" size="large">
          <el-table-column prop="orderNo" label="订单号" min-width="130" show-overflow-tooltip />
          <el-table-column prop="username" label="用户" width="90" />
          <el-table-column label="商品" min-width="105">
            <template #default="{ row }">{{ kindLabel(row) }}</template>
          </el-table-column>
          <el-table-column label="原价" width="78">
            <template #default="{ row }"><span class="mono-sm">¥{{ fen2yuan(row.originalFen) }}</span></template>
          </el-table-column>
          <el-table-column label="实付" width="98">
            <template #default="{ row }">
              <span class="payable mono-sm">¥{{ fen2yuan(row.payableFen) }}</span>
              <div v-if="row.couponCode" class="sub-line">{{ row.couponCode }}</div>
            </template>
          </el-table-column>
          <el-table-column label="渠道" width="70">
            <template #default="{ row }">
              {{ channelLabel(row.payChannel) }}
            </template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="orderMetaOf(row).tag" effect="light">
                {{ orderMetaOf(row).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="140">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <template v-if="row.status === 'CREATED'">
                <el-button size="small" type="success" plain @click="confirm(row)">确认到账</el-button>
                <el-button size="small" type="danger" plain @click="reject(row)">拒单</el-button>
              </template>
              <span v-else-if="row.adminRemark" class="sub-line">{{ row.adminRemark }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
        </el-table>

        <!-- 手机卡片 -->
        <div v-else class="cards" v-loading="ordersLoading">
          <el-empty v-if="!filteredOrders.length" description="没有订单" :image-size="70" />
          <div v-for="row in filteredOrders" :key="row.id" class="row-card">
            <div class="card-top">
              <strong>{{ kindLabel(row) }}</strong>
              <el-tag :type="orderMetaOf(row).tag" size="small" effect="light">
                {{ orderMetaOf(row).label }}
              </el-tag>
            </div>
            <div class="card-meta">
              <span>{{ row.username }} · ¥{{ fen2yuan(row.payableFen) }}</span>
              <span>{{ formatDateTime(row.createdAt) }}</span>
            </div>
            <div v-if="row.status === 'CREATED'" class="card-actions">
              <el-button size="small" type="success" plain @click="confirm(row)">确认到账</el-button>
              <el-button size="small" type="danger" plain @click="reject(row)">拒单</el-button>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- ================= 优惠码 ================= -->
      <el-tab-pane label="优惠码" name="coupons" lazy>
        <div class="toolbar">
          <span class="stats">共 {{ coupons.length }} 个码</span>
          <div class="toolbar-actions">
            <el-button type="primary" @click="genVisible = true">生成优惠码</el-button>
            <el-button :icon="Refresh" @click="loadCoupons">刷新</el-button>
          </div>
        </div>

        <div v-if="couponSelected.length" class="batch-bar">
          <span class="batch-count">已选 <b>{{ couponSelected.length }}</b> 个</span>
          <div class="batch-actions">
            <el-button size="small" type="warning" plain @click="couponBatch('disable')">禁用</el-button>
            <el-button size="small" type="success" plain @click="couponBatch('enable')">启用</el-button>
            <el-button size="small" type="danger" plain :icon="Delete" @click="couponBatch('delete')">删除</el-button>
            <el-button size="small" text @click="couponSelected = []">取消选择</el-button>
          </div>
        </div>

        <el-table
          v-if="!isMobile"
          :data="coupons"
          v-loading="couponsLoading"
          size="large"
          row-key="code"
          @selection-change="onCouponSelect"
        >
          <el-table-column type="selection" width="46" />
          <el-table-column label="码" min-width="160">
            <template #default="{ row }">
              <span class="code mono-sm" @click="copyText(row.code, '已复制')">{{ row.code }}</span>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="110">
            <template #default="{ row }">{{ couponKindLabel(row.kind) }}</template>
          </el-table-column>
          <el-table-column label="面额" min-width="110">
            <template #default="{ row }">
              <span v-if="row.kind === 'DISCOUNT'">{{ (row.valuePercent / 10).toFixed(1) }} 折</span>
              <span v-else-if="row.kind === 'QUOTA'">{{ row.quotaCount }} 次额度</span>
              <span v-else-if="row.kind === 'DATE_PASS'">解锁任一日期</span>
              <span v-else>月卡</span>
            </template>
          </el-table-column>
          <el-table-column label="用量" width="100">
            <template #default="{ row }">
              <span class="mono-sm">{{ row.usedCount }}/{{ row.maxUses === 0 ? '不限' : row.maxUses }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.disabled" type="danger" effect="light">已禁用</el-tag>
              <el-tag v-else-if="row.remainingUses === 0" type="info" effect="light">已用尽</el-tag>
              <el-tag v-else type="success" effect="light">可用</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="到期" min-width="140">
            <template #default="{ row }">{{ row.expiresAt ? formatDateTime(row.expiresAt) : '永久' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button v-if="!row.disabled" size="small" type="warning" plain @click="toggleCoupon(row)">禁用</el-button>
              <el-button v-else size="small" type="success" plain @click="toggleCoupon(row)">启用</el-button>
              <el-button size="small" :icon="CopyDocument" @click="copyText(row.code, '已复制')" />
            </template>
          </el-table-column>
        </el-table>

        <div v-else class="cards" v-loading="couponsLoading">
          <el-empty v-if="!coupons.length" description="还没有优惠码" :image-size="70" />
          <div v-for="row in coupons" :key="row.code" class="row-card">
            <div class="card-top">
              <span class="code mono-sm">{{ row.code }}</span>
              <el-tag size="small" effect="light">{{ couponKindLabel(row.kind) }}</el-tag>
            </div>
            <div class="card-meta">
              <span>用量 {{ row.usedCount }}/{{ row.maxUses === 0 ? '不限' : row.maxUses }}</span>
              <span>{{ row.disabled ? '已禁用' : '可用' }}</span>
            </div>
            <div class="card-actions">
              <el-button size="small" :icon="CopyDocument" @click="copyText(row.code, '已复制')">复制</el-button>
              <el-button v-if="!row.disabled" size="small" type="warning" plain @click="toggleCoupon(row)">禁用</el-button>
              <el-button v-else size="small" type="success" plain @click="toggleCoupon(row)">启用</el-button>
            </div>
          </div>
        </div>

        <!-- 生成对话框 -->
        <el-dialog v-model="genVisible" title="生成优惠码" width="460px" align-center>
          <el-form label-position="top">
            <el-form-item label="类型">
              <el-radio-group v-model="genForm.kind">
                <el-radio-button value="DATE_PASS">单日兑换码</el-radio-button>
                <el-radio-button value="MONTH_PASS">月卡兑换码</el-radio-button>
                <el-radio-button value="QUOTA">额度码</el-radio-button>
                <el-radio-button value="DISCOUNT">折扣码</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="生成数量">
              <el-input-number v-model="genForm.count" :min="1" :max="50" />
            </el-form-item>
            <el-form-item v-if="genForm.kind === 'DISCOUNT'" label="折扣（80 = 8 折）">
              <el-input-number v-model="genForm.valuePercent" :min="1" :max="99" />
              <el-radio-group v-model="genForm.appliesTo" class="ml">
                <el-radio-button value="ALL">全部商品</el-radio-button>
                <el-radio-button value="DATE">仅单日卡</el-radio-button>
                <el-radio-button value="MONTH">仅月卡</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item v-if="genForm.kind === 'QUOTA'" label="赠送免费解锁次数">
              <el-input-number v-model="genForm.quotaCount" :min="1" :max="99" />
            </el-form-item>
            <el-form-item label="每个码可用次数">
              <div class="gen-uses">
                <el-input-number v-model="genForm.maxUses" :min="1" :max="9999" :disabled="genForm.unlimited" />
                <el-checkbox v-model="genForm.unlimited">不限次数（对外销售可开）</el-checkbox>
              </div>
            </el-form-item>
            <el-form-item label="单用户最多使用">
              <el-input-number v-model="genForm.perUserLimit" :min="1" :max="99" />
            </el-form-item>
            <el-form-item label="有效天数">
              <el-input-number v-model="genForm.expiresDays" :min="0" :max="3650" />
              <span class="gen-hint-inline">0 = 永不过期</span>
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="genVisible = false">取消</el-button>
            <el-button type="primary" :loading="generating" @click="generateCoupons">生成并复制</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- ================= 活动与支付设置 ================= -->
      <el-tab-pane label="支付与活动" name="pay" lazy>
        <div class="pay-grid">
          <div class="panel-card">
            <h4>定价与收款渠道</h4>
            <el-form label-position="top">
              <el-form-item label="单日解锁价（元）">
                <el-input-number v-model="settingsForm.datePassYuan" :min="0.01" :max="999" :step="1" />
              </el-form-item>
              <el-form-item label="月卡价（元）">
                <el-input-number v-model="settingsForm.monthYuan" :min="0.01" :max="9999" :step="1" />
              </el-form-item>
              <el-form-item label="月卡天数">
                <el-input-number v-model="settingsForm.monthDays" :min="1" :max="365" />
              </el-form-item>
              <el-form-item label="收款渠道">
                <el-radio-group v-model="settingsForm.payChannel">
                  <el-radio-button value="LISTEN">免签监听（自动）</el-radio-button>
                  <el-radio-button value="MANUAL">人工确认</el-radio-button>
                  <el-radio-button value="XUNHU">虎皮椒</el-radio-button>
                </el-radio-group>
                <p class="form-hint">
                  监听渠道：用户按「精确金额」扫码付款，pay-listener 电脑端监听工具自动回调核销；
                  虎皮椒需在 local.yml 配置 YKT_XUNHU_APPID / YKT_XUNHU_SECRET 后启用。
                </p>
              </el-form-item>
              <el-form-item label="监听令牌（配置到 pay-listener/config.ini）">
                <div class="token-row">
                  <code class="mono-sm">{{ settings?.listenToken }}</code>
                  <el-button size="small" :icon="CopyDocument" @click="copyText(settings?.listenToken ?? '', '令牌已复制')" />
                </div>
              </el-form-item>
              <el-form-item label="支付说明（展示在付费页底部）">
                <el-input v-model="settingsForm.payNote" type="textarea" :rows="2" placeholder="如：支付后 1 分钟内自动到账；遇到问题联系管理员。" />
              </el-form-item>
              <el-button type="primary" :loading="paySaving" @click="saveSettings">保存设置</el-button>
            </el-form>

            <h4 class="mt">单日按日期定价</h4>
            <p class="form-hint">
              默认所有日期 ¥{{ fen2yuan(settings?.datePassPriceFen ?? 300) }}；某一天要单独改价就在下面改（如节假日加价、特价促销），保存后立即生效。
            </p>
            <el-table :data="batchRows" size="small" max-height="320">
              <el-table-column prop="batch" label="日期" width="90" />
              <el-table-column label="卷子" width="70">
                <template #default="{ row }">{{ row.papersCount }} 套</template>
              </el-table-column>
              <el-table-column label="价格（元）" min-width="140">
                <template #default="{ row }">
                  <el-input-number
                    v-model="row.priceYuan"
                    :min="0.01"
                    :max="999"
                    :step="1"
                    size="small"
                    controls-position="right"
                  />
                </template>
              </el-table-column>
              <el-table-column label="状态" width="80">
                <template #default="{ row }">
                  <el-tag v-if="row.isCustom" type="warning" effect="light" size="small">单独价</el-tag>
                  <el-tag v-else type="info" effect="light" size="small">默认</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="150" fixed="right">
                <template #default="{ row }">
                  <el-button
                    size="small"
                    type="primary"
                    plain
                    :loading="batchSaving === row.batch"
                    @click="saveBatchPrice(row)"
                  >保存</el-button>
                  <el-button
                    v-if="row.isCustom"
                    size="small"
                    :loading="batchSaving === row.batch"
                    @click="resetBatchPrice(row.batch)"
                  >恢复默认</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="panel-card">
            <h4>收款码</h4>
            <p class="form-hint">上传个人微信/支付宝收款码（静态码即可，用户扫码后手动输入精确金额）。</p>
            <div class="qr-row">
              <div class="qr-slot">
                <span class="qr-label">微信</span>
                <div class="qr-box">
                  <img v-if="qrWechat" :src="qrWechat" alt="微信收款码" />
                  <span v-else>未上传</span>
                </div>
                <el-upload
                  :show-file-list="false"
                  accept="image/*"
                  :auto-upload="false"
                  :on-change="onQrUpload('wechat')"
                >
                  <el-button size="small">{{ qrWechat ? '更换' : '上传' }}</el-button>
                </el-upload>
                <el-button v-if="qrWechat" size="small" text type="danger" @click="clearQr('wechat')">清除</el-button>
              </div>
              <div class="qr-slot">
                <span class="qr-label">支付宝</span>
                <div class="qr-box">
                  <img v-if="qrAlipay" :src="qrAlipay" alt="支付宝收款码" />
                  <span v-else>未上传</span>
                </div>
                <el-upload
                  :show-file-list="false"
                  accept="image/*"
                  :auto-upload="false"
                  :on-change="onQrUpload('alipay')"
                >
                  <el-button size="small">{{ qrAlipay ? '更换' : '上传' }}</el-button>
                </el-upload>
                <el-button v-if="qrAlipay" size="small" text type="danger" @click="clearQr('alipay')">清除</el-button>
              </div>
            </div>

            <h4 class="mt">限时活动</h4>
            <p class="form-hint">窗口期内下单自动享受折扣，与用户优惠码取最优、不叠加。</p>
            <el-table :data="activities" size="small">
              <el-table-column prop="name" label="名称" min-width="120" />
              <el-table-column label="折扣" width="90">
                <template #default="{ row }">{{ (row.discountPercent / 10).toFixed(1) }} 折</template>
              </el-table-column>
              <el-table-column label="适用" width="90">
                <template #default="{ row }">
                  {{ appliesLabel(row.appliesTo) }}
                </template>
              </el-table-column>
              <el-table-column label="窗口" min-width="200">
                <template #default="{ row }">
                  {{ row.startsAt ? formatDateTime(row.startsAt) : '不限' }} ~
                  {{ row.endsAt ? formatDateTime(row.endsAt) : '不限' }}
                </template>
              </el-table-column>
              <el-table-column label="操作" width="80">
                <template #default="{ row }">
                  <el-button size="small" type="danger" plain @click="removeActivity(row.id)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-button class="mt" type="primary" plain @click="actVisible = true">新建活动</el-button>
          </div>
        </div>

        <el-dialog v-model="actVisible" title="新建限时活动" width="440px" align-center>
          <el-form label-position="top">
            <el-form-item label="活动名称">
              <el-input v-model="actForm.name" placeholder="如：双11 月卡特惠" />
            </el-form-item>
            <el-form-item label="折扣（80 = 8 折）">
              <el-input-number v-model="actForm.discountPercent" :min="1" :max="99" />
            </el-form-item>
            <el-form-item label="适用商品">
              <el-radio-group v-model="actForm.appliesTo">
                <el-radio-button value="ALL">全部</el-radio-button>
                <el-radio-button value="DATE">仅单日卡</el-radio-button>
                <el-radio-button value="MONTH">仅月卡</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="生效窗口">
              <el-date-picker
                v-model="actForm.range"
                type="datetimerange"
                start-placeholder="开始时间"
                end-placeholder="结束时间"
                class="w-full"
              />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="actVisible = false">取消</el-button>
            <el-button type="primary" :loading="actSaving" @click="addActivity">创建</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- ================= 用户权益与绑定 ================= -->
      <el-tab-pane label="用户权益" name="users" lazy>
        <div class="pay-grid">
          <div class="panel-card">
            <h4>发放权益</h4>
            <el-form label-position="top">
              <el-form-item label="作用范围">
                <el-radio-group v-model="grantForm.scope">
                  <el-radio-button value="one">单个用户</el-radio-button>
                  <el-radio-button value="all">全部普通用户</el-radio-button>
                </el-radio-group>
              </el-form-item>
              <el-form-item v-if="grantForm.scope === 'one'" label="用户">
                <el-select v-model="grantForm.userId" filterable placeholder="选择用户" class="w-full">
                  <el-option
                    v-for="u in users"
                    :key="u.id"
                    :value="u.id"
                    :label="`${u.username}（${u.role}）`"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="权益类型">
                <el-radio-group v-model="grantForm.kind">
                  <el-radio-button value="MONTH">月卡</el-radio-button>
                  <el-radio-button value="DATE">解锁日期</el-radio-button>
                  <el-radio-button value="QUOTA">调额度（正加负减）</el-radio-button>
                </el-radio-group>
              </el-form-item>
              <el-form-item v-if="grantForm.kind === 'MONTH'" label="天数">
                <el-input-number v-model="grantForm.days" :min="1" :max="365" />
              </el-form-item>
              <el-form-item v-if="grantForm.kind === 'DATE'" label="日期批次">
                <el-select v-model="grantForm.targetBatch" placeholder="选择批次" class="w-full">
                  <el-option v-for="b in batches" :key="b" :value="b" :label="b" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="grantForm.kind === 'QUOTA'" label="额度变化（可为负）">
                <el-input-number v-model="grantForm.quota" :min="-999" :max="999" />
              </el-form-item>
              <el-button
                type="primary"
                :loading="usersLoading"
                @click="doGrant"
              >
                {{ grantForm.scope === 'all' ? '向全部普通用户发放' : '发放' }}
              </el-button>
            </el-form>
          </div>

          <div class="panel-card">
            <h4>雨课堂绑定</h4>
            <p class="form-hint">1:1 绑定防帮做；用户扫码不一致会被拒。解绑后可重新绑定。</p>
            <el-empty v-if="!bindings.length" description="还没有绑定记录" :image-size="60" />
            <el-table v-else :data="bindings" size="small">
              <el-table-column prop="username" label="用户" width="90" />
              <el-table-column prop="yktName" label="雨课堂账号" min-width="120" />
              <el-table-column label="绑定时间" min-width="140">
                <template #default="{ row }">{{ formatDateTime(row.boundAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="80">
                <template #default="{ row }">
                  <el-button size="small" type="danger" plain @click="unbind(row)">解绑</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <div class="panel-card mt">
          <h4>管理操作审计（最近 100 条，敏感操作全程留痕）</h4>
          <el-table :data="auditLogs" size="small" max-height="320">
            <el-table-column label="时间" width="150">
              <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column prop="adminUsername" label="管理员" width="100" />
            <el-table-column prop="action" label="操作" width="130" />
            <el-table-column prop="target" label="对象" min-width="120" show-overflow-tooltip />
            <el-table-column prop="detail" label="详情" min-width="180" show-overflow-tooltip />
            <el-table-column prop="ip" label="IP" width="120" show-overflow-tooltip />
          </el-table>
        </div>

        <div class="panel-card mt">
          <h4>权益记录</h4>
          <el-table :data="entitlements" size="small" max-height="420">
            <el-table-column prop="username" label="用户" width="90" />
            <el-table-column label="类型" width="120">
              <template #default="{ row }">
                {{ row.kind === 'MONTH' ? '月卡' : `日期 ${row.targetBatch}` }}
              </template>
            </el-table-column>
            <el-table-column label="到期" min-width="150">
              <template #default="{ row }">
                {{ row.kind === 'MONTH' ? formatDateTime(row.expiresAt) : '永久' }}
              </template>
            </el-table-column>
            <el-table-column label="来源" min-width="130">
              <template #default="{ row }">{{ row.sourceOrderNo || '后台发放' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.revoked" type="danger" effect="light">已撤销</el-tag>
                <el-tag v-else type="success" effect="light">生效中</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button v-if="!row.revoked" size="small" type="danger" plain @click="revoke(row)">撤销</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.admin-vip {
  padding: 20px 24px;
}
.head h2 {
  margin: 0 0 6px;
}
.head p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}
.vip-tabs {
  margin-top: 10px;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.toolbar-actions {
  display: flex;
  gap: 8px;
}
.stats {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.stats b {
  color: var(--el-text-color-primary);
  font-size: 15px;
}
.stats b.warn {
  color: var(--el-color-warning);
}
.match-box {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}
.batch-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
  padding: 10px 14px;
  border: 1px solid var(--accent-200);
  border-radius: var(--r-md);
  background: var(--accent-050);
}
.batch-count {
  font-size: 13px;
  color: var(--ink-600);
}
.batch-count b {
  color: var(--accent-700);
  font-size: 15px;
}
.batch-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.code {
  font-weight: 700;
  color: var(--ink-800);
  cursor: copy;
}
.code:hover {
  color: var(--accent);
}
.payable {
  font-weight: 700;
  color: var(--amber);
}
.sub-line {
  font-size: 11.5px;
  color: var(--ink-400);
  margin-top: 2px;
}
.muted {
  color: var(--ink-300);
}

.pay-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  align-items: start;
}
.panel-card {
  padding: 16px 18px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
}
.panel-card h4 {
  margin: 0 0 10px;
  font-size: 15px;
}
.panel-card h4.mt,
.panel-card .mt {
  margin-top: 18px;
}
.form-hint {
  margin: 4px 0 10px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}
.gen-hint-inline {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.gen-uses {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.ml {
  margin-left: 14px;
}
.token-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.token-row code {
  padding: 6px 10px;
  border-radius: var(--r-sm);
  background: var(--surface-3);
  word-break: break-all;
}
.qr-row {
  display: flex;
  gap: 26px;
  flex-wrap: wrap;
}
.qr-slot {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-start;
}
.qr-label {
  font-size: 12.5px;
  color: var(--ink-500);
}
.qr-box {
  width: 110px;
  height: 110px;
  display: grid;
  place-items: center;
  overflow: hidden;
  border: 1px dashed var(--border);
  border-radius: var(--r-md);
  background: var(--surface-2);
  font-size: 12px;
  color: var(--ink-400);
}
.qr-box img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.w-full {
  width: 100%;
}

/* 手机卡片（订单/优惠码共用） */
.cards {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.row-card {
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--surface);
}
.card-top {
  display: flex;
  align-items: center;
  gap: 8px;
}
.card-top strong {
  flex: 1;
  font-size: 14px;
}
.card-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 7px;
  font-size: 12.5px;
  color: var(--ink-600);
  flex-wrap: wrap;
}
.card-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

/* —— 到账记录卡 —— */
.notes-card {
  margin-bottom: 14px;
  padding: 12px 14px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-md);
}
.notes-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}

/* —— 订单统计条 —— */
.vip-stats {
  display: grid;
  /* 自适应列数：宽屏 6 列，窄屏自动换行，柱图列固定独占一行 */
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
  padding: 14px 16px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
}
.vs-chart {
  grid-column: 1 / -1;
}
.vs-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.vs-label {
  font-size: 12px;
  color: var(--ink-500);
}
.vs-q {
  display: inline-grid;
  place-items: center;
  width: 13px;
  height: 13px;
  border-radius: 50%;
  background: var(--surface-3);
  color: var(--ink-400);
  font-size: 10px;
  cursor: help;
}
.vs-channel {
  font-size: 15px !important;
}
.vs-cell strong {
  font-size: 19px;
  font-weight: 800;
  color: var(--ink-900);
  white-space: nowrap;
}
.vs-cell strong.vs-gold {
  color: var(--ink-900);
}
.vs-cell em {
  font-style: normal;
  font-size: 11.5px;
  color: var(--ink-400);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.vs-cell em b.vs-warn {
  color: var(--el-color-warning);
}
.vs-chart .vs-bars {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  align-items: end;
  gap: 4px;
  height: 46px;
  margin-top: 2px;
}
.vs-bar-col {
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  align-items: center;
  gap: 2px;
  min-width: 0;
}
.vs-bar {
  width: 100%;
  max-width: 12px;
  min-width: 5px;
  border-radius: 3px 3px 0 0;
  background: var(--accent);
  opacity: 0.9;
}
.vs-bar-date {
  font-size: 9px;
  color: var(--ink-400);
  transform: scale(0.85);
  white-space: nowrap;
  max-width: 100%;
  overflow: hidden;
}

@media (max-width: 768px) {
  .admin-vip {
    padding: 14px 12px 28px;
  }
  .vip-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .vs-chart {
    grid-column: 1 / -1;
  }
  .vs-chart .vs-bars {
    height: 52px;
  }
  .pay-grid {
    grid-template-columns: 1fr;
  }
  .toolbar {
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
  }
  .match-box {
    flex-direction: column;
  }
  .match-box .el-button {
    align-self: flex-start;
  }
}
</style>
