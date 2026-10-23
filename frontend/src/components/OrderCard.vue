<script setup lang="ts">
import { ArrowUpRight, Check } from '@lucide/vue'
import type { Order } from '../types'
import { formatAmount, formatDate } from './format'

defineProps<{
  order: Order
  selected?: boolean
  busy?: boolean
  compact?: boolean
  historical?: boolean
}>()
defineEmits<{ select: [order: Order]; confirmReceipt: [order: Order] }>()
</script>

<template>
  <article
    class="order-card"
    :class="{ 'is-selected': selected, 'is-compact': compact }"
    data-testid="order-card"
  >
    <div class="order-card-heading">
      <span class="order-number">{{ order.orderNo }}</span>
      <span
        class="status-badge"
        :data-status="order.status"
      >
        <span
          class="status-dot"
          aria-hidden="true"
        ></span>
        {{ order.statusDescription || order.status }}
      </span>
    </div>
    <p class="order-card-meta">{{ formatDate(order.createdAt) }} · 店铺 {{ order.shopId }}</p>
    <p
      v-if="order.receivedAt"
      class="order-card-meta"
    >
      确认收货：{{ formatDate(order.receivedAt, true) }}
    </p>
    <div class="order-card-bottom">
      <span class="order-amount">{{ formatAmount(order.totalAmount, order.currency) }}</span>
      <button
        type="button"
        class="order-consult"
        :disabled="busy"
        :aria-label="`咨询此订单 ${order.orderNo}`"
        @click="$emit('select', order)"
      >
        <Check
          v-if="selected"
          :size="13"
          aria-hidden="true"
        />
        {{ historical ? '查询最新订单' : selected ? '继续咨询' : '咨询此订单' }}
        <ArrowUpRight
          v-if="!selected"
          :size="13"
          aria-hidden="true"
        />
      </button>
    </div>
    <button
      v-if="!historical && order.status === 'SHIPPED' && !order.receivedAt"
      type="button"
      class="order-consult"
      :disabled="busy"
      :aria-label="`确认收货 ${order.orderNo}`"
      @click="$emit('confirmReceipt', order)"
    >
      确认收货
    </button>
  </article>
</template>
