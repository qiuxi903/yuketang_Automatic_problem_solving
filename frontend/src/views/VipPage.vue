<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Check, Coin, CreditCard, MagicStick, Refresh, Trophy } from '@element-plus/icons-vue'
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

const purchasableBatches = computed(
  () => products.value?.batches.filter((b) => !b.unlocked) ?? [],
)

/** 单日卡当前实价：套用生效活动折扣后的应付分 */
const selectedBatchPriceFen = computed(() => {
  const picked = purchasableBatches.value.find((b) => b.batch === pickBatch.value)
  return picked?.priceFen ?? products.value?.datePassPriceFen ?? 0
})

const datePriceFen = computed(() => {
  const base = selectedBatchPriceFen.value
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

/** 已解锁日期展示：前 3 个 + 剩余数量 */
const unlockedChips = computed(() => {
  const list = status.value?.unlockedBatches ?? []
  return { shown: list.slice(0, 3), extra: Math.max(0, list.length - 3) }
})

const monthDaysLeft = computed(() => {
  const expires = status.value?.monthCardExpiresAt
  if (!expires || !status.value?.monthCardActive) return 0
  return Math.max(0, Math.ceil((new Date(expires).getTime() - Date.now()) / 86400000))
})

/* 权益状态卡（与概览页 stat-card 同款） */
const statusCards = computed(() => {
  const s = status.value
  if (!s) return []
  return [
    {
      label: '月卡',
      value: s.monthCardActive ? `剩 ${monthDaysLeft.value} 天` : '未开通',
      sub: s.monthCardActive && s.monthCardExpiresAt ? `到期 ${formatDateTime(s.monthCardExpiresAt)}` : '期内所有日期无限做',
    },
    {
      label: '免费额度',
      value: `${s.freeQuota} 次`,
      sub: '可解锁任意一个日期',
    },
    {
      label: '已解锁日期',
      value: `${s.unlockedBatches.length} 个`,
      sub: unlockedChips.value.shown.length
        ? unlockedChips.value.shown.join(' / ') + (unlockedChips.value.extra ? ` 等 ${unlockedChips.value.extra} 个` : '')
        : '解锁后永久可用',
    },
    {
      label: '绑定雨课堂',
      value: s.boundYktName || '待绑定',
      sub: s.boundYktName ? '1:1 绑定，解绑需管理员' : '首次扫码登录自动绑定',
    },
  ]
})

/* 支付弹层倒计时（mm:ss）；到期自动停轮询并提示重新下单 */
const payCountdown = computed(() => countdown(activeOrder.value?.expiresAt ?? null, nowTick.value))
watch(payCountdown, (c) => {
  if (c.expired && payOpen.value && !payResult.value) {
    stopPolling()
    ElMessage.warning('订单支付窗口已过期：请取消该订单后重新下单（金额尾数会变）')
  }
})

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
  claimHint.value = null
  payOpen.value = true
  startPolling()
}

function startPolling() {
  stopPolling()
  pollTimer = window.setInterval(async () => {
    if (!activeOrder.value) return
    try {
      const list = await ordersRefresh()
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

/** 「我已支付，核实到账」的核实结果提示（未核实到时展示在弹层里，轮询继续） */
const claimHint = ref<string | null>(null)

async function claimPaid() {
  if (!activeOrder.value) return
  try {
    const res = await claimVipOrderPaid(activeOrder.value.orderNo)
    if (res.verified) {
      payResult.value = 'success'
      claimHint.value = null
      stopPolling()
      ElMessage.success(res.message)
      await refresh()
    } else {
      claimHint.value = res.message
      ElMessage.warning(res.message)
      // 保持轮询：到账记录入池后会自动匹配开通
    }
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
  <div v-loading="loading" class="vip-page">
    <div class="page-inner">
      <!-- 页头：与全站 page-head 同款 -->
      <header class="page-head">
        <div>
          <span class="eyebrow">VIP 会员中心</span>
          <h2>解锁日期与月卡</h2>
          <p class="page-sub">
            自动做题按日期解锁，一次付费永久可用；月卡期内所有日期无限做。
            答案中心浏览与下载永远免费。
            <template v-if="products?.activity">
              限时活动「{{ products.activity.name }}」进行中，全场
              <strong>{{ (products.activity.discountPercent / 10).toFixed(1) }} 折</strong>。
            </template>
          </p>
        </div>
        <div class="head-actions">
          <el-button :icon="Refresh" @click="refresh">刷新</el-button>
          <el-button :icon="ArrowLeft" @click="backToConsole">返回做题台</el-button>
        </div>
      </header>

      <!-- 权益状态：与概览页 stat-card 同款 -->
      <div v-if="status" class="stat-grid">
        <div v-for="card in statusCards" :key="card.label" class="stat-card">
          <span class="stat-label">{{ card.label }}</span>
          <strong class="stat-value">{{ card.value }}</strong>
          <span class="stat-sub">{{ card.sub }}</span>
        </div>
      </div>

      <!-- 定价区 -->
      <div class="pricing">
        <!-- 单日解锁 -->
        <div class="price-card">
          <header class="pc-head">
            <span class="pc-icon"><el-icon><CreditCard /></el-icon></span>
            <div>
              <h3>单日解锁</h3>
              <p>解锁指定日期的全部卷子 · 永久可用</p>
            </div>
          </header>
          <div class="pc-price">
            <template v-if="datePriceFen !== selectedBatchPriceFen">
              <del>¥{{ fen2yuan(selectedBatchPriceFen) }}</del>
              <strong>¥{{ fen2yuan(datePriceFen) }}</strong>
            </template>
            <strong v-else>¥{{ fen2yuan(selectedBatchPriceFen) }}</strong>
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
              :label="`${b.batch}（${b.papersCount} 套卷 · ¥${fen2yuan(b.priceFen)}）`"
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
            type="primary"
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
            <span class="pc-icon is-month"><el-icon><Trophy /></el-icon></span>
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
          <el-button type="primary" size="large" class="pc-buy" :loading="buying" @click="buyMonthCard">
            开通月卡
          </el-button>
        </div>
      </div>

      <!-- 优惠码 + 兑换中心（panel-card 与管理后台同款） -->
      <div class="panels">
        <div class="panel-card">
          <h4><el-icon><MagicStick /></el-icon> 优惠码</h4>
          <p class="panel-hint">有优惠码就先填上，下单时自动按最优折扣计算（与活动不叠加，取更便宜的）。</p>
          <el-input
            v-model="couponInput"
            placeholder="例如 YH-AB2C-3DEF"
            size="large"
            clearable
          />
        </div>

        <div class="panel-card">
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
            <el-button type="primary" size="large" :loading="redeeming" @click="redeem">
              兑换
            </el-button>
          </div>
        </div>
      </div>

      <!-- 订单记录 -->
      <div v-if="orders.length" class="orders">
        <h4 class="orders-title">我的订单</h4>
        <el-table :data="orders" size="large" class="orders-table mobile-hide">
          <el-table-column prop="orderNo" label="订单号" min-width="150" show-overflow-tooltip />
          <el-table-column label="商品" min-width="140">
            <template #default="{ row }">{{ kindLabel(row) }}</template>
          </el-table-column>
          <el-table-column label="实付" width="110">
            <template #default="{ row }">
              <span class="money">¥{{ fen2yuan(row.payableFen) }}</span>
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
                type="primary"
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
              <span class="money">¥{{ fen2yuan(row.payableFen) }}</span>
              <span>{{ formatDateTime(row.createdAt) }}</span>
            </div>
            <el-button
              v-if="row.status === 'CREATED'"
              size="small"
              type="primary"
              plain
              @click="openPayDialog(row)"
            >
              继续支付
            </el-button>
          </li>
        </ul>
      </div>

      <p v-if="status?.payNote" class="pay-note">{{ status.payNote }}</p>
    </div>

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
          <el-button type="primary" size="large" @click="payOpen = false; backToConsole()">
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
              人工确认渠道：请按上方金额向管理员支付，支付完成后点「我已支付，核实到账」，管理员确认后立即开通。
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
            <el-button size="large" type="primary" plain @click="claimPaid">我已支付，核实到账</el-button>
          </div>
          <el-alert
            v-if="claimHint"
            class="pd-claim-hint"
            type="warning"
            :title="claimHint"
            :closable="false"
          />
          <p class="pd-hint">支付完成后点上方按钮核实到账；本页也会每 3 秒自动检测，到账即开通。</p>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script lang="ts">
export default { name: 'VipPage' }
</script>

<style scoped>
/* 与全站统一：page-inner 版心 + page-head + stat-card + panel-card，无独立品牌渐变 */
.vip-page {
  height: 100%;
  overflow-y: auto;
  background: var(--paper);
}
.page-inner {
  max-width: 1020px;
  margin: 0 auto;
  padding: 20px 24px 48px;
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.page-head .eyebrow {
  color: var(--amber);
}
.page-head h2 {
  margin: 6px 0;
}
.page-sub {
  margin: 0;
  max-width: 640px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink-500);
}
.page-sub strong {
  color: var(--amber);
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}

/* —— 权益状态卡（与概览页 stat-card 同款） —— */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 18px;
}
.stat-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 18px 20px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-xs);
  min-width: 0;
}
.stat-label {
  font-size: 12.5px;
  color: var(--ink-400);
}
.stat-value {
  font-family: var(--font-display);
  font-size: 26px;
  line-height: 1.1;
  color: var(--ink-900);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.stat-sub {
  font-size: 12px;
  color: var(--ink-500);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* —— 定价卡（白卡片 + 主色边框标注推荐，无大面积色块） —— */
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
  box-shadow: var(--shadow-xs);
}
.price-card.is-month {
  border-color: var(--accent);
  box-shadow: var(--shadow-sm);
}
.pc-ribbon {
  position: absolute;
  top: -10px;
  right: 18px;
  padding: 3px 12px;
  border-radius: 99px;
  background: var(--accent);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  box-shadow: var(--shadow-xs);
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
  background: var(--accent-100);
  color: var(--accent-700);
  font-size: 20px;
}
.pc-icon.is-month {
  background: var(--amber-050);
  color: var(--amber);
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
  font-size: 32px;
  font-weight: 800;
  color: var(--ink-900);
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
  background: var(--surface-3);
  color: var(--ink-600);
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
  color: var(--accent);
}
.pc-buy {
  width: 100%;
  margin-top: auto;
}
.pc-buy + .pc-buy {
  margin-left: 0;
}

/* —— 优惠码 / 兑换（panel-card 与管理后台同款） —— */
.panels {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
  margin-top: 18px;
}
.panel-card {
  padding: 18px 20px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
}
.panel-card h4 {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 6px;
  font-size: 15px;
}
.panel-card h4 .el-icon {
  color: var(--accent-700);
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

/* 金额统一用琥珀色（与管理后台实付列同款） */
.money {
  font-family: var(--font-mono, monospace);
  font-weight: 700;
  color: var(--amber);
}

/* —— 支付弹层（白卡片，无品牌色块） —— */
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
  font-size: 38px;
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
.pd-claim-hint {
  width: 100%;
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

/* —— 移动端：与全站同款断点，单列自适应 —— */
@media (max-width: 768px) {
  .page-inner {
    padding: 16px 14px 34px;
  }
  .page-head {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }
  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }
  .stat-card {
    padding: 14px 16px;
  }
  .stat-value {
    font-size: 22px;
  }
  .pricing,
  .panels {
    grid-template-columns: 1fr;
  }
  .coupon-row {
    flex-wrap: wrap;
  }
  .coupon-row .el-input {
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
