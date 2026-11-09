import client from './client'

/** VIP 付费/权益接口（/api/vip/** 用户侧，/api/admin/vip/** 管理侧）。金额一律为「分」。 */

export interface VipStatus {
  enforce: boolean
  monthCardExpiresAt: string | null
  monthCardActive: boolean
  freeQuota: number
  unlockedBatches: string[]
  boundYktName: string | null
  payChannel: 'LISTEN' | 'MANUAL' | 'XUNHU'
  payNote: string | null
}

export interface VipActivityView {
  id: number
  name: string
  /** 折扣百分数：80 = 8 折 */
  discountPercent: number
  appliesTo: 'DATE' | 'MONTH' | 'ALL'
  startsAt: string | null
  endsAt: string | null
  enabled: boolean
}

export interface VipBatchView {
  batch: string
  papersCount: number
  unlocked: boolean
  /** 该日期的单日解锁价（分）；未单独定价 = 全局默认价 */
  priceFen: number
}

export interface VipProducts {
  datePassPriceFen: number
  monthPriceFen: number
  monthDays: number
  payChannel: 'LISTEN' | 'MANUAL' | 'XUNHU'
  payNote: string | null
  hasQrWechat: boolean
  hasQrAlipay: boolean
  activity: VipActivityView | null
  batches: VipBatchView[]
}

export type VipOrderKind = 'DATE_PASS' | 'MONTH_CARD'
export type VipOrderStatus = 'CREATED' | 'PAID' | 'CANCELLED' | 'REJECTED'

export interface VipOrder {
  id: number
  orderNo: string
  userId: number
  username: string | null
  kind: VipOrderKind
  targetBatch: string | null
  originalFen: number
  discountFen: number
  /** 免签监听用的随机分尾数 */
  suffixFen: number
  payableFen: number
  status: VipOrderStatus
  payChannel: 'LISTEN' | 'MANUAL' | 'XUNHU'
  couponCode: string | null
  remark: string | null
  adminRemark: string | null
  claimedAt: string | null
  paidAt: string | null
  expiresAt: string
  createdAt: string
  activityName: string | null
}

export interface VipOrderCreatePayload {
  kind: VipOrderKind
  targetBatch?: string | null
  couponCode?: string
  remark?: string
}

/* —— 管理侧 —— */

export interface VipCouponView {
  id: number
  code: string
  kind: 'DISCOUNT' | 'DATE_PASS' | 'MONTH_PASS' | 'QUOTA'
  valuePercent: number | null
  quotaCount: number | null
  appliesTo: 'DATE' | 'MONTH' | 'ALL' | null
  maxUses: number
  perUserLimit: number
  usedCount: number
  remainingUses: number
  disabled: boolean
  expiresAt: string | null
  createdAt: string
  createdBy: string | null
}

export interface VipCouponCreatePayload {
  count: number
  kind: VipCouponView['kind']
  valuePercent?: number
  quotaCount?: number
  appliesTo?: 'DATE' | 'MONTH' | 'ALL'
  /** 0 = 不限次数；不传 = 1 */
  maxUses?: number
  perUserLimit?: number
  /** 有效天数；不传 = 永不过期 */
  expiresDays?: number
}

export interface VipEntitlementView {
  id: number
  userId: number
  username: string | null
  kind: 'DATE' | 'MONTH'
  targetBatch: string | null
  expiresAt: string | null
  revoked: boolean
  sourceOrderNo: string | null
  createdAt: string
}

export interface VipBindingView {
  id: number
  userId: number
  username: string | null
  yktName: string
  autoBound: boolean
  boundAt: string
}

export interface VipSettingsView {
  datePassPriceFen: number
  monthPriceFen: number
  monthDays: number
  payChannel: 'LISTEN' | 'MANUAL' | 'XUNHU'
  hasQrWechat: boolean
  hasQrAlipay: boolean
  payNote: string | null
  listenToken: string
}

export interface VipSettingsUpdatePayload {
  datePassPriceFen?: number
  monthPriceFen?: number
  monthDays?: number
  payChannel?: VipSettingsView['payChannel']
  /** null/缺省 = 不改；空串 = 清除 */
  payQrWechat?: string
  payQrAlipay?: string
  payNote?: string
}

export interface VipStats {
  totalRevenueFen: number
  todayRevenueFen: number
  /** 近 7 日收入（含今日，升序） */
  dailyRevenue: { date: string; revenueFen: number; orders: number }[]
  paidOrders: number
  pendingOrders: number
  cancelledOrders: number
  rejectedOrders: number
  monthActiveUsers: number
  dateEntitlements: number
  freeQuotaTotal: number
  couponsActive: number
  couponsUsed: number
}

/* —— 用户侧 —— */

export function getVipStatus() {
  return client.get<unknown, VipStatus>('/vip/status')
}

export function getVipProducts() {
  return client.get<unknown, VipProducts>('/vip/products')
}

/** 收款码图片（base64 dataURL），未上传的渠道为 null。 */
export function getPayQrImages() {
  return client.get<unknown, { wechat: string | null; alipay: string | null }>('/vip/pay-qr')
}

export function createVipOrder(payload: VipOrderCreatePayload) {
  return client.post<unknown, VipOrder>('/vip/orders', payload)
}

export function listMyVipOrders() {
  return client.get<unknown, VipOrder[]>('/vip/orders')
}

/** 「我已支付，核实到账」：verified=true 表示已核实到账并开通。 */
export function claimVipOrderPaid(orderNo: string) {
  return client.post<unknown, { verified: boolean; message: string }>(
    `/vip/orders/${encodeURIComponent(orderNo)}/claim-paid`,
  )
}

export function cancelVipOrder(orderNo: string) {
  return client.post<unknown, { ok: boolean }>(`/vip/orders/${encodeURIComponent(orderNo)}/cancel`)
}

export function redeemVipCode(code: string, targetBatch?: string) {
  return client.post<unknown, { ok: boolean; message: string }>('/vip/redeem', { code, targetBatch })
}

export function unlockWithQuota(targetBatch: string) {
  return client.post<unknown, { ok: boolean }>('/vip/unlock-quota', { targetBatch })
}

/* —— 管理侧 —— */

export function listVipOrders(status?: VipOrderStatus) {
  return client.get<unknown, VipOrder[]>('/admin/vip/orders', {
    params: status ? { status } : undefined,
  })
}

/** VIP 运营统计：收入、订单分布、会员与权益、额度、优惠码。 */
export function getVipStats() {
  return client.get<unknown, VipStats>('/admin/vip/stats')
}

export function confirmVipOrder(orderNo: string, adminRemark?: string) {
  return client.post<unknown, VipOrder>(`/admin/vip/orders/${encodeURIComponent(orderNo)}/confirm`, {
    adminRemark,
  })
}

export function rejectVipOrder(orderNo: string, adminRemark?: string) {
  return client.post<unknown, VipOrder>(`/admin/vip/orders/${encodeURIComponent(orderNo)}/reject`, {
    adminRemark,
  })
}

/** 粘贴收款通知文本快速核销（监听漏单兜底）。 */
export function matchPayText(raw: string) {
  return client.post<unknown, { matched: boolean }>('/admin/vip/orders/match-text', { raw })
}

export function listVipCoupons() {
  return client.get<unknown, VipCouponView[]>('/admin/vip/coupons')
}

export function generateVipCoupons(payload: VipCouponCreatePayload) {
  return client.post<unknown, { codes: string[] }>('/admin/vip/coupons', payload)
}

export function disableVipCoupon(code: string) {
  return client.put<unknown, void>(`/admin/vip/coupons/${encodeURIComponent(code)}/disable`)
}

export function enableVipCoupon(code: string) {
  return client.put<unknown, void>(`/admin/vip/coupons/${encodeURIComponent(code)}/enable`)
}

export function batchVipCoupons(action: 'disable' | 'enable' | 'delete', codes: string[]) {
  return client.post<unknown, { affected: number }>('/admin/vip/coupons/batch', { action, codes })
}

export function listVipEntitlements() {
  return client.get<unknown, VipEntitlementView[]>('/admin/vip/entitlements')
}

/** kind：MONTH（days）/ DATE（targetBatch）/ QUOTA（quota，正加负减）。 */
export function grantVip(userId: number, payload: { kind: 'MONTH' | 'DATE' | 'QUOTA'; days?: number; targetBatch?: string; quota?: number }) {
  return client.post<unknown, { ok: boolean; message: string }>(`/admin/vip/users/${userId}/grant`, payload)
}

export function revokeVipEntitlement(id: number) {
  return client.delete<unknown, void>(`/admin/vip/entitlements/${id}`)
}

export function listVipBindings() {
  return client.get<unknown, VipBindingView[]>('/admin/vip/bindings')
}

export function unbindYkt(userId: number) {
  return client.delete<unknown, void>(`/admin/vip/users/${userId}/binding`)
}

export function listVipActivities() {
  return client.get<unknown, VipActivityView[]>('/admin/vip/activities')
}

export function saveVipActivity(activity: Partial<VipActivityView> & { name: string; discountPercent: number }) {
  return client.post<unknown, VipActivityView>('/admin/vip/activities', activity)
}

export function deleteVipActivity(id: number) {
  return client.delete<unknown, void>(`/admin/vip/activities/${id}`)
}

export function getVipSettings() {
  return client.get<unknown, VipSettingsView>('/admin/vip/settings')
}

export function updateVipSettings(payload: VipSettingsUpdatePayload) {
  return client.put<unknown, VipSettingsView>('/admin/vip/settings', payload)
}

/* —— 到账记录池（管理端） —— */

export interface PayNotificationView {
  id: number
  channel: string
  amountFen: number
  raw: string | null
  receivedAt: string
  consumed: boolean
  consumedByOrderNo: string | null
}

/** 最近到账记录（含未核销，监听漏单核对用）。 */
export function listPayNotifications() {
  return client.get<unknown, PayNotificationView[]>('/admin/vip/pay-notifications')
}

export function deletePayNotification(id: number) {
  return client.delete<unknown, void>(`/admin/vip/pay-notifications/${id}`)
}

/* —— 单日按批次定价（管理端） —— */

export interface VipBatchPriceView {
  batch: string
  priceFen: number
  updatedAt: string
}

export function listBatchPrices() {
  return client.get<unknown, VipBatchPriceView[]>('/admin/vip/batch-prices')
}

export function setBatchPrice(batch: string, priceFen: number) {
  return client.put<unknown, VipBatchPriceView>('/admin/vip/batch-prices', { batch, priceFen })
}

export function deleteBatchPrice(batch: string) {
  return client.delete<unknown, void>(`/admin/vip/batch-prices/${encodeURIComponent(batch)}`)
}
