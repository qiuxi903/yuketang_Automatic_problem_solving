<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Check, Coin, CreditCard, MagicStick, Medal, Trophy } from '@element-plus/icons-vue'
import {
  cancelVipOrder,
  claimVipOrderPaid,
  createVipOrder,
  getPayQrImages,
  getVipProducts,
  getVipStatus,
  listMyVipOrders,
  redeemVipCode,
  unlockWithQuota,
  type VipOrder,
  type VipOrderStatus,
  type VipProducts,
  type VipStatus,
} from '@/api/vip'
import { countdown, formatDateTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const status = ref<VipStatus | null>(null)
const products = ref<VipProducts | null>(null)
const orders = ref<VipOrder[]>([])

/* —— 购买表单 —— */
const pickBatch = ref('')
const couponInput = ref('')
const buying = ref(false)

/* —— 兑换中心 —— */
const redeemCode = ref('')
const redeemBatch = ref('')
const redeeming = ref(false)

/* —— 支付弹层 —— */
const payOpen = ref(false)
const activeOrder = ref<VipOrder | null>(null)
const qrImages = ref<{ wechat: string | null; alipay: string | null }>({ wechat: null, alipay: null })
let pollTimer: number | null = null
let tickTimer: number | null = null
const nowTick = ref(Date.now())
const payResult = ref<'success' | null>(null)

const fen2yuan = (fen: number) => (fen / 100).toFixed(2)

const unlockedSet = computed(() => new Set(status.value?.unlockedBatches ?? []))
const purchasableBatches = computed(
  () => products.value?.batches.filter((b) => !b.unlocked) ?? [],
)

/** 单日卡当前实价：套用生效活动折扣后的应付分 */
const datePriceFen = computed(() => {
  const base = products.value?.datePassPriceFen ?? 0
  const act = products.value?.activity
  if (!act || !appliesTo(act.appliesTo, 'DATE')) return base
  return Math.round(base * act.discountPercent / 100)
})
const monthPriceFen = computed(() => {
  const base = products.value?.monthPriceFen ?? 0
  const act = products.value?.activity
  if (!act || !appliesTo(act.appliesTo, 'MONTH')) return base
  return Math.round(base * act.discountPercent / 100)
})

function appliesTo(kind: 'DATE' | 'MONTH' | 'ALL', target: 'DATE' | 'MONTH') {
  return kind === 'ALL' || kind === target
}

const monthDaysLeft = computed(() => {
  const expires = status.value?.monthCardExpiresAt
  if (!expires || !status.value?.monthCardActive) return 0
  return Math.max(0, Math.ceil((new Date(expires).getTime() - Date.now()) / 86400000))
})

/* 支付弹层倒计时（mm:ss），到期提示刷新订单 */
const payCountdown = computed(() => countdown(activeOrder.value?.expiresAt ?? null, nowTick.value))

async function refresh() {
  try {
    const [s, p, o, qr] = await Promise.all([
      getVipStatus(),
      getVipProducts(),
      listMyVipOrders(),
      getPayQrImages(),
    ])
    status.value = s
    products.value = p
    orders.value = o
    qrImages.value = qr
    // 从做题台 402 跳转过来时预选要解锁的日期
    const fromBatch = String(route.query.batch ?? '').trim()
    if (fromBatch && !pickBatch.value) {
      pickBatch.value = p.batches.some((b) => b.batch === fromBatch) ? fromBatch : ''
    }
    if (!pickBatch.value && purchasableBatches.value.length) {
      pickBatch.value = purchasableBatches.value[0].batch
    }
    if (!redeemBatch.value && purchasableBatches.value.length) {
      redeemBatch.value = purchasableBatches.value[0].batch
    }
  } catch {
    /* 错误提示由 axios 拦截器统一处理 */
  } finally {
    loading.value = false
  }
}

async function buyDatePass() {
  if (!pickBatch.value) {
    ElMessage.warning('请选择要解锁的日期')
    return
  }
  buying.value = true
  try {
    const order = await createVipOrder({
      kind: 'DATE_PASS',
      targetBatch: pickBatch.value,
      couponCode: couponInput.value.trim() || undefined,
    })
    openPayDialog(order)
  } catch {
    /* 拦截器已提示 */
  } finally {
    buying.value = false
  }
}

async function buyMonthCard() {
  buying.value = true
  try {
    const order = await createVipOrder({
      kind: 'MONTH_CARD',
      couponCode: couponInput.value.trim() || undefined,
    })
    openPayDialog(order)
  } catch {
    /* 拦截器已提示 */
  } finally {
    buying.value = false
  }
}

function openPayDialog(order: VipOrder) {
  activeOrder.value = order
  payResult.value = null
  payOpen.value = true
  startPolling()
}

function startPolling() {
  stopPolling()
  pollTimer = window.setInterval(async () => {
    if (!activeOrder.value) return
    try {
      const fresh = ordersRefresh()
      const list = await fresh
      const target = list.find((o) => o.orderNo === activeOrder.value?.orderNo)
      if (target) activeOrder.value = target
      if (target && target.status === 'PAID') {
        payResult.value = 'success'
        stopPolling()
        ElMessage.success('支付已确认，权益已生效')
        await refresh()
      }
    } catch {
      // 轮询失败不中断：网络抖动时下一轮继续（轮询 catch 直接 stop 是历史坑）
    }
  }, 3000)
  tickTimer = window.setInterval(() => {
    nowTick.value = Date.now()
  }, 1000)
}

async function ordersRefresh(): Promise<VipOrder[]> {
  orders.value = await listMyVipOrders()
  return orders.value
}

function stopPolling() {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
  if (tickTimer !== null) {
    window.clearInterval(tickTimer)
    tickTimer = null
  }
}

async function claimPaid() {
  if (!activeOrder.value) return
  try {
    await claimVipOrderPaid(activeOrder.value.orderNo)
    ElMessage.info('已标记「我已付款」，请等待自动到账或管理员确认')
  } catch {
    /* 拦截器已提示 */
  }
}

async function cancelActiveOrder() {
  if (!activeOrder.value) return
  try {
    await ElMessageBox.confirm('确定取消该订单吗？取消后需重新下单。', '取消订单', {
      confirmButtonText: '取消订单',
      cancelButtonText: '继续支付',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await cancelVipOrder(activeOrder.value.orderNo)
    ElMessage.info('订单已取消')
    payOpen.value = false
    stopPolling()
    await refresh()
  } catch {
    /* 拦截器已提示 */
  }
}

async function redeem() {
  const code = redeemCode.value.trim()
  if (!code) {
    ElMessage.warning('请输入兑换码')
    return
  }
  redeeming.value = true
  try {
    const res = await redeemVipCode(code, redeemBatch.value || undefined)
    ElMessage.success(res.message || '兑换成功')
    redeemCode.value = ''
    await refresh()
  } catch {
    /* 拦截器已提示 */
  } finally {
    redeeming.value = false
  }
}

/** 消耗 1 次免费额度解锁所选日期（注册赠送/后台发放的体验额度）。 */
const quotaUnlocking = ref(false)
async function unlockByQuota() {
  if (!pickBatch.value) {
    ElMessage.warning('请选择要解锁的日期')
    return
  }
  try {
    await ElMessageBox.confirm(
      `使用 1 次免费额度解锁日期「${pickBatch.value}」？（剩余 ${status.value?.freeQuota ?? 0} 次，解锁后永久可用）`,
      '免费额度解锁',
      { confirmButtonText: '解锁', cancelButtonText: '再想想', type: 'info' },
    )
  } catch {
    return
  }
  quotaUnlocking.value = true
  try {
    await unlockWithQuota(pickBatch.value)
    ElMessage.success(`日期 ${pickBatch.value} 已解锁，回做题台开做吧`)
    await refresh()
  } catch {
    /* 拦截器已提示 */
  } finally {
    quotaUnlocking.value = false
  }
}

const orderStatusMeta: Record<VipOrderStatus, { label: string; tone: 'ok' | 'info' | 'warn' | 'danger' }> = {
  CREATED: { label: '待支付', tone: 'warn' },
  PAID: { label: '已支付', tone: 'ok' },
  CANCELLED: { label: '已取消', tone: 'info' },
  REJECTED: { label: '已拒单', tone: 'danger' },
}

/** 模板槽位的 row 是 any，走类型化辅助函数取映射。 */
function orderMetaOf(row: VipOrder) {
  return orderStatusMeta[row.status]
}

const kindLabel = (order: VipOrder) =>
  order.kind === 'DATE_PASS' ? `单日解锁 · ${order.targetBatch}` : '月卡'

function backToConsole() {
  router.push({ name: 'console' })
}

onMounted(() => {
  void refresh()
})

onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="vip-page">
    <!-- —— 顶部金质横幅 —— -->
    <header class="hero">
      <div class="hero-inner">
        <button class="back" type="button" @click="backToConsole">
          <el-icon><ArrowLeft /></el-icon>
          <span>返回做题台</span>
        </button>

        <div class="hero-copy">
          <span class="hero-badge"><el-icon><Trophy /></el-icon>MEMBERSHIP</span>
          <h1>解锁全部题库，<em>一次付费长期有效</em></h1>
          <p>
            单日卡按日期解锁，一个日期只需付一次钱；月卡期内无限做题。
            答案中心的答案浏览与下载<b>永远免费</b>，付费功能只有自动做题。
            <template v-if="products?.activity">
              限时活动「{{ products.activity.name }}」进行中，全场
              <strong>{{ (products.activity.discountPercent / 10).toFixed(1) }} 折</strong>。
            </template>
          </p>
        </div>

        <div v-if="status" class="hero-status">
          <div class="stat">
            <span class="stat-label"><el-icon><Medal /></el-icon> 月卡</span>
            <strong v-if="status.monthCardActive" class="stat-gold">
              剩 {{ monthDaysLeft }} 天
            </strong>
            <strong v-else>未开通</strong>
          </div>
          <div class="stat">
            <span class="stat-label"><el-icon><Coin /></el-icon> 免费额度</span>
            <strong>{{ status.freeQuota }} 次</strong>
          </div>
          <div class="stat">
            <span class="stat-label"><el-icon><Check /></el-icon> 已解锁日期</span>
            <strong>{{ status.unlockedBatches.length }} 个</strong>
          </div>
          <div class="stat">
            <span class="stat-label">绑定雨课堂</span>
            <strong>{{ status.boundYktName || '首次扫码自动绑定' }}</strong>
          </div>
        </div>
      </div>
    </header>

    <main class="body">
      <section v-loading="loading" class="content">
        <!-- —— 定价区 —— -->
        <div class="pricing">
          <!-- 单日卡 -->
          <div class="price-card">
            <header class="pc-head">
              <span class="pc-icon"><el-icon><CreditCard /></el-icon></span>
              <div>
                <h3>单日解锁</h3>
                <p>解锁指定日期的全部卷子 · 永久可用</p>
              </div>
            </header>
            <div class="pc-price">
              <template v-if="datePriceFen !== (products?.datePassPriceFen ?? 0)">
                <del>¥{{ fen2yuan(products?.datePassPriceFen ?? 0) }}</del>
                <strong>¥{{ fen2yuan(datePriceFen) }}</strong>
              </template>
              <strong v-else>¥{{ fen2yuan(products?.datePassPriceFen ?? 0) }}</strong>
              <span>/ 日期</span>
            </div>
            <el-select
              v-model="pickBatch"
              placeholder="选择要解锁的日期"
              class="pc-select"
              size="large"
            >
              <el-option
                v-for="b in purchasableBatches"
                :key="b.batch"
                :value="b.batch"
                :label="`${b.batch}（${b.papersCount} 套卷）`"
              />
            </el-select>
            <p v-if="!purchasableBatches.length" class="pc-hint">
              当前题库日期都已解锁，直接回做题台开做即可
            </p>
            <ul class="pc-list">
              <li><el-icon><Check /></el-icon>该日期下所有卷子自动做题、无限次重做</li>
              <li><el-icon><Check /></el-icon>一次付费，永久解锁该日期</li>
              <li><el-icon><Check /></el-icon>取答案不限；答案中心下载永远免费</li>
            </ul>
            <el-button
              type="warning"
              size="large"
              class="pc-buy"
              :disabled="!purchasableBatches.length"
              :loading="buying"
              @click="buyDatePass"
            >
              解锁所选日期
            </el-button>
            <el-button
              v-if="status && status.freeQuota > 0"
              size="large"
              class="pc-buy"
              plain
              :disabled="!pickBatch"
              :loading="quotaUnlocking"
              @click="unlockByQuota"
            >
              用免费额度解锁（剩 {{ status.freeQuota }} 次）
            </el-button>
          </div>

          <!-- 月卡 -->
          <div class="price-card is-month">
            <span class="pc-ribbon">推荐</span>
            <header class="pc-head">
              <span class="pc-icon"><el-icon><Trophy /></el-icon></span>
              <div>
                <h3>月卡会员</h3>
                <p>{{ products?.monthDays ?? 30 }} 天内所有日期无限做题</p>
              </div>
            </header>
            <div class="pc-price">
              <template v-if="monthPriceFen !== (products?.monthPriceFen ?? 0)">
                <del>¥{{ fen2yuan(products?.monthPriceFen ?? 0) }}</del>
                <strong>¥{{ fen2yuan(monthPriceFen) }}</strong>
              </template>
              <strong v-else>¥{{ fen2yuan(products?.monthPriceFen ?? 0) }}</strong>
              <span>/ {{ products?.monthDays ?? 30 }} 天</span>
            </div>
            <div class="pc-month-placeholder">
              全部日期 · 全部卷子 · 期内无限做
            </div>
            <ul class="pc-list">
              <li><el-icon><Check /></el-icon>所有日期批次全部解锁</li>
              <li><el-icon><Check /></el-icon>期内新上线的日期自动可用</li>
              <li><el-icon><Check /></el-icon>到期前续费自动顺延</li>
            </ul>
            <el-button type="warning" size="large" class="pc-buy" :loading="buying" @click="buyMonthCard">
              开通月卡
            </el-button>
          </div>
        </div>

        <!-- —— 优惠码 + 兑换中心 —— -->
        <div class="panels">
          <div class="panel">
            <h4><el-icon><MagicStick /></el-icon> 优惠码</h4>
            <p class="panel-hint">有优惠码就先填上，下单时自动按最优折扣计算（与活动不叠加，取更便宜的）。</p>
            <div class="coupon-row">
              <el-input
                v-model="couponInput"
                placeholder="例如 YH-AB2C-3DEF"
                size="large"
                clearable
                class="coupon-input"
              />
            </div>
          </div>

          <div class="panel">
            <h4><el-icon><Coin /></el-icon> 兑换中心</h4>
            <p class="panel-hint">
              月卡兑换码 / 额度码直接兑换秒到账；单日兑换码需选好日期再兑。
            </p>
            <div class="coupon-row">
              <el-input
                v-model="redeemCode"
                placeholder="输入兑换码"
                size="large"
                clearable
                class="coupon-input"
                @keyup.enter="redeem"
              />
              <el-select
                v-model="redeemBatch"
                placeholder="解锁日期（单日码）"
                size="large"
                class="redeem-batch"
              >
                <el-option
                  v-for="b in purchasableBatches"
                  :key="b.batch"
                  :value="b.batch"
                  :label="b.batch"
                />
              </el-select>
              <el-button type="warning" size="large" :loading="redeeming" @click="redeem">
                兑换
              </el-button>
            </div>
          </div>
        </div>

        <!-- —— 订单记录 —— -->
        <div v-if="orders.length" class="orders">
          <h4 class="orders-title">我的订单</h4>
          <el-table :data="orders" size="large" class="orders-table mobile-hide">
            <el-table-column prop="orderNo" label="订单号" min-width="150" show-overflow-tooltip />
            <el-table-column label="商品" min-width="140">
              <template #default="{ row }">{{ kindLabel(row) }}</template>
            </el-table-column>
            <el-table-column label="实付" width="110">
              <template #default="{ row }">
                <span class="mono">¥{{ fen2yuan(row.payableFen) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="orderMetaOf(row).tone" effect="light">
                  {{ orderMetaOf(row).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="170">
              <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.status === 'CREATED'"
                  size="small"
                  type="warning"
                  plain
                  @click="openPayDialog(row)"
                >
                  继续支付
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <!-- 手机卡片 -->
          <ul class="orders-cards mobile-only">
            <li v-for="row in orders" :key="row.id" class="order-card">
              <div class="oc-top">
                <strong>{{ kindLabel(row) }}</strong>
                <el-tag :type="orderMetaOf(row).tone" effect="light">
                  {{ orderMetaOf(row).label }}
                </el-tag>
              </div>
              <div class="oc-sub">
                <span class="mono">¥{{ fen2yuan(row.payableFen) }}</span>
                <span>{{ formatDateTime(row.createdAt) }}</span>
              </div>
              <el-button
                v-if="row.status === 'CREATED'"
                size="small"
                type="warning"
                plain
                @click="openPayDialog(row)"
              >
                继续支付
              </el-button>
            </li>
          </ul>
        </div>

        <p v-if="status?.payNote" class="pay-note">{{ status.payNote }}</p>
      </section>
    </main>

    <!-- —— 支付弹层 —— -->
    <el-dialog
      v-model="payOpen"
      width="460px"
      align-center
      class="pay-dialog"
      :show-close="!payResult"
      @close="stopPolling"
    >
      <template #header>
        <span class="pd-title">{{ payResult ? '支付成功' : '扫码支付' }}</span>
      </template>

      <template v-if="activeOrder">
        <!-- 成功态 -->
        <div v-if="payResult" class="pd-success">
          <span class="pd-check"><el-icon><Check /></el-icon></span>
          <strong>已到账</strong>
          <p>
            {{ activeOrder.kind === 'DATE_PASS' ? `日期 ${activeOrder.targetBatch} 已解锁` : '月卡已生效' }}，
            回做题台即可继续做题。
          </p>
          <el-button type="warning" size="large" @click="payOpen = false; backToConsole()">
            回去做题
          </el-button>
        </div>

        <!-- 支付态 -->
        <div v-else class="pd-body">
          <div class="pd-amount">
            <span>应付</span>
            <strong>¥{{ fen2yuan(activeOrder.payableFen) }}</strong>
            <em v-if="activeOrder.suffixFen > 0">
              含 {{ (activeOrder.suffixFen / 100).toFixed(2) }} 元随机尾数，用于自动到账识别，请按显示金额精确支付
            </em>
          </div>

          <div v-if="activeOrder.payChannel === 'LISTEN' && qrImages.wechat" class="pd-qrs">
            <div class="pd-qr-row">
              <figure class="pd-qr">
                <div class="pd-qr-box"><img :src="qrImages.wechat" alt="微信收款码" /></div>
                <figcaption>微信</figcaption>
              </figure>
              <figure v-if="qrImages.alipay" class="pd-qr">
                <div class="pd-qr-box"><img :src="qrImages.alipay" alt="支付宝收款码" /></div>
                <figcaption>支付宝</figcaption>
              </figure>
            </div>
            <p class="pd-qr-tip">任选一码扫码支付，金额务必按上方显示精确输入（含尾数）</p>
          </div>
          <div v-else class="pd-noqr">
            <template v-if="activeOrder.payChannel === 'LISTEN'">
              管理员尚未上传收款码：请按上方金额联系管理员支付，到账后自动开通。
            </template>
            <template v-else-if="activeOrder.payChannel === 'MANUAL'">
              人工确认渠道：请按上方金额向管理员支付，支付完成后点「我已完成付款」，管理员确认后立即开通。
            </template>
            <template v-else>
              虎皮椒支付渠道已选择，但支付页尚未接入跳转——请管理员完成渠道联调，或切回收款码/人工渠道。
            </template>
          </div>

          <div class="pd-meta">
            <span>订单号 <b class="mono">{{ activeOrder.orderNo }}</b></span>
            <span>支付剩余 <b :class="{ 'is-urgent': payCountdown.expired }">{{ payCountdown.text }}</b></span>
          </div>
          <p v-if="activeOrder.couponCode" class="pd-coupon">
            已用优惠码 <b>{{ activeOrder.couponCode }}</b>，立减 ¥{{ fen2yuan(activeOrder.discountFen) }}
          </p>

          <div class="pd-actions">
            <el-button size="large" @click="cancelActiveOrder">取消订单</el-button>
            <el-button size="large" type="warning" plain @click="claimPaid">我已完成付款</el-button>
          </div>
          <p class="pd-hint">支付完成后本页会自动检测到账并开通（每 3 秒刷新一次），无需手动刷新。</p>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script lang="ts">
export default { name: 'VipPage' }
</script>

<style scoped>
.vip-page {
  height: 100%;
  overflow-y: auto;
  background: var(--paper);
}

/* —— 顶部横幅 —— */
.hero {
  background:
    radial-gradient(720px 300px at 88% -40%, rgba(255, 255, 255, 0.2), transparent 70%),
    linear-gradient(150deg, #8a5a08, var(--amber) 55%, #e09a2b);
  color: #fff;
}
.hero-inner {
  max-width: 1020px;
  margin: 0 auto;
  padding: 26px 24px 30px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.back {
  align-self: flex-start;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: none;
  padding: 7px 14px;
  border-radius: 99px;
  background: rgba(255, 255, 255, 0.16);
  color: #fff;
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color 0.16s ease;
}
.back:hover {
  background: rgba(255, 255, 255, 0.28);
}

.hero-copy h1 {
  margin: 12px 0 8px;
  font-size: 30px;
  font-weight: 800;
  letter-spacing: -0.02em;
}
.hero-copy h1 em {
  font-style: normal;
  color: #ffe9b8;
}
.hero-copy p {
  margin: 0;
  max-width: 640px;
  font-size: 13.5px;
  line-height: 1.75;
  color: rgba(255, 255, 255, 0.86);
}
.hero-copy p strong {
  color: #ffe9b8;
}
.hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  border-radius: 99px;
  background: rgba(255, 255, 255, 0.18);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.hero-status {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}
.stat {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: rgba(255, 255, 255, 0.14);
  backdrop-filter: blur(2px);
}
.stat-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.78);
}
.stat strong {
  font-size: 15px;
  font-weight: 800;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.stat-gold {
  color: #ffe9b8;
}

/* —— 主体 —— */
.body {
  padding: 26px 24px 48px;
}
.content {
  max-width: 1020px;
  margin: 0 auto;
  min-height: 260px;
}

.pricing {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
}
.price-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 22px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-sm);
}
.price-card.is-month {
  border-color: var(--amber);
  box-shadow: 0 6px 24px rgba(194, 118, 14, 0.16);
}
.pc-ribbon {
  position: absolute;
  top: -10px;
  right: 18px;
  padding: 3px 12px;
  border-radius: 99px;
  background: var(--amber);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  box-shadow: var(--shadow-sm);
}
.pc-head {
  display: flex;
  gap: 12px;
  align-items: center;
}
.pc-icon {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  flex: none;
  border-radius: 12px;
  background: var(--amber-050);
  color: var(--amber);
  font-size: 20px;
}
.pc-head h3 {
  margin: 0 0 2px;
  font-size: 17px;
}
.pc-head p {
  margin: 0;
  font-size: 12.5px;
  color: var(--ink-500);
}
.pc-price {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.pc-price strong {
  font-size: 34px;
  font-weight: 800;
  color: var(--amber);
  letter-spacing: -0.02em;
}
.pc-price del {
  font-size: 15px;
  color: var(--ink-400);
}
.pc-price span {
  font-size: 13px;
  color: var(--ink-500);
}
.pc-select {
  width: 100%;
}
.pc-month-placeholder {
  padding: 9px 14px;
  border-radius: var(--r-sm);
  background: var(--amber-050);
  color: var(--amber);
  font-size: 13px;
  font-weight: 600;
}
.pc-hint {
  margin: 0;
  font-size: 12.5px;
  color: var(--ink-500);
}
.pc-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.pc-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--ink-600);
}
.pc-list li .el-icon {
  color: var(--amber);
}
.pc-buy {
  width: 100%;
  margin-top: auto;
}

/* —— 优惠码 / 兑换 —— */
.panels {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
  margin-top: 18px;
}
.panel {
  padding: 18px 20px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
}
.panel h4 {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 6px;
  font-size: 15px;
}
.panel h4 .el-icon {
  color: var(--amber);
}
.panel-hint {
  margin: 0 0 12px;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--ink-500);
}
.coupon-row {
  display: flex;
  gap: 10px;
}
.coupon-input {
  flex: 1;
}
.redeem-batch {
  width: 170px;
}

/* —— 订单记录 —— */
.orders {
  margin-top: 26px;
}
.orders-title {
  margin: 0 0 12px;
  font-size: 15px;
}
.orders-cards {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.order-card {
  padding: 13px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--surface);
}
.oc-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 14px;
}
.oc-sub {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-top: 6px;
  font-size: 12.5px;
  color: var(--ink-500);
}
.pay-note {
  margin: 22px 0 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--ink-500);
}

/* —— 支付弹层 —— */
.pd-title {
  font-size: 16px;
  font-weight: 700;
}
.pd-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.pd-amount {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  padding: 14px 0 4px;
}
.pd-amount span {
  font-size: 13px;
  color: var(--ink-500);
}
.pd-amount strong {
  font-size: 40px;
  font-weight: 800;
  color: var(--amber);
  letter-spacing: -0.02em;
}
.pd-amount em {
  font-style: normal;
  font-size: 12px;
  color: var(--ink-400);
  text-align: center;
}
.pd-qrs {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.pd-qr-row {
  display: flex;
  gap: 22px;
}
.pd-qr {
  margin: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.pd-qr-box {
  width: 190px;
  height: 190px;
  display: grid;
  place-items: center;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  overflow: hidden;
  background: var(--surface-2);
}
.pd-qr-box img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.pd-qr figcaption {
  font-size: 12.5px;
  color: var(--ink-500);
}
.pd-qr-tip {
  margin: 0;
  font-size: 12px;
  color: var(--ink-400);
  text-align: center;
}
.pd-noqr {
  padding: 14px;
  border-radius: var(--r-md);
  background: var(--surface-2);
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink-600);
  text-align: center;
}
.pd-meta {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 12.5px;
  color: var(--ink-500);
}
.pd-meta b.is-urgent {
  color: var(--danger);
}
.pd-coupon {
  margin: 0;
  font-size: 12.5px;
  color: var(--ink-500);
}
.pd-actions {
  display: flex;
  gap: 10px;
}
.pd-actions .el-button {
  flex: 1;
}
.pd-hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-400);
  text-align: center;
}
.pd-success {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 10px 0 6px;
  text-align: center;
}
.pd-check {
  display: grid;
  place-items: center;
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: var(--ok-100);
  color: var(--ok);
  font-size: 34px;
}
.pd-success strong {
  font-size: 18px;
}
.pd-success p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink-500);
}

.mono {
  font-family: var(--font-mono, monospace);
}

/* —— 暗色微调 —— */
html.dark .hero {
  background:
    radial-gradient(720px 300px at 88% -40%, rgba(255, 255, 255, 0.12), transparent 70%),
    linear-gradient(150deg, #4b3207, #7d5510 55%, #9a6a15);
}

/* —— 移动端 —— */
@media (max-width: 768px) {
  .hero-inner {
    padding: 18px 16px 22px;
  }
  .hero-copy h1 {
    font-size: 22px;
  }
  .hero-status {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .body {
    padding: 16px 14px 34px;
  }
  .pricing,
  .panels {
    grid-template-columns: 1fr;
  }
  .coupon-row {
    flex-wrap: wrap;
  }
  .coupon-input {
    flex: 1 1 100%;
  }
  .redeem-batch {
    flex: 1;
    width: auto;
  }
  .pd-actions {
    flex-direction: column;
  }
}
</style>
