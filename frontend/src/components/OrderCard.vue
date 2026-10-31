<script setup lang="ts">
import { ArrowUpRight, Check, Package, Trash2 } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { isOrderProductImageUrl } from '../productImages'
import type { Order } from '../types'
import { formatAmount, formatDate } from './format'

const props = defineProps<{
  order: Order
  selected?: boolean
  busy?: boolean
  compact?: boolean
  historical?: boolean
  deleted?: boolean
  deletionBlocked?: boolean
}>()
defineEmits<{
  select: [order: Order]
  confirmReceipt: [order: Order]
  deleteOrder: [order: Order]
}>()

const failedImageUrls = ref(new Set<string>())
const demoImageUrls = new Set([
  '/products/demo/black-earbuds.png',
  '/products/demo/white-mouse.png',
  '/products/demo/white-mug.png',
  '/products/demo/cotton-bedding.png',
])
const previewItems = computed(() =>
  (props.order.items ?? []).slice(0, 3).map((item) => ({
    ...item,
    imageUrl: isOrderProductImageUrl(item.imageUrl) ? item.imageUrl : null,
  })),
)
watch(
  () => props.order,
  () => {
    failedImageUrls.value = new Set()
  },
)
</script>

<template>
  <article
    class="order-card"
    :class="{ 'is-selected': selected, 'is-compact': compact, 'has-products': order.items?.length }"
    data-testid="order-card"
    :data-shop-id="order.shopId"
    :data-order-no="order.orderNo"
  >
    <ul
      v-if="order.items?.length"
      class="order-item-preview"
      aria-label="购买商品"
    >
      <li
        v-for="(item, index) in previewItems"
        :key="index"
        class="order-product-row"
      >
        <div class="order-product-image">
          <template v-if="item.imageUrl && !failedImageUrls.has(item.imageUrl)">
            <img
              :src="item.imageUrl"
              :alt="item.productName"
              width="72"
              height="72"
              loading="lazy"
              decoding="async"
              @error="failedImageUrls.add(item.imageUrl)"
            />
            <span
              v-if="demoImageUrls.has(item.imageUrl)"
              class="order-product-demo"
            >
              演示图
            </span>
          </template>
          <div
            v-else
            class="order-product-placeholder"
          >
            <Package
              :size="23"
              aria-hidden="true"
            />
            <span>暂无图片</span>
          </div>
        </div>
        <div class="order-product-details">
          <strong
            class="order-product-name"
            data-testid="order-product-name"
          >
            {{ item.productName }}
          </strong>
          <span class="order-product-meta">
            <template v-if="item.specification">{{ item.specification }} ·</template>
            数量 {{ item.quantity }}
          </span>
        </div>
      </li>
      <li
        v-if="(order.itemKinds ?? order.items.length) > order.items.length"
        class="order-product-more"
      >
        另有 {{ (order.itemKinds ?? order.items.length) - order.items.length }} 种商品
      </li>
    </ul>
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
      <span class="order-amount">
        <span class="order-total-label">合计</span>
        {{ formatAmount(order.totalAmount, order.currency) }}
      </span>
      <button
        type="button"
        class="order-consult"
        :disabled="busy || deleted"
        :aria-label="`咨询此订单 ${order.orderNo}`"
        @click="$emit('select', order)"
      >
        <Check
          v-if="selected"
          :size="13"
          aria-hidden="true"
        />
        {{
          deleted
            ? '此订单已删除'
            : historical
              ? '查询最新订单'
              : selected
                ? '继续咨询'
                : '咨询此订单'
        }}
        <ArrowUpRight
          v-if="!selected"
          :size="13"
          aria-hidden="true"
        />
      </button>
    </div>
    <button
      v-if="!historical && !deleted && order.status === 'SHIPPED' && !order.receivedAt"
      type="button"
      class="order-consult"
      :disabled="busy"
      :aria-label="`确认收货 ${order.orderNo}`"
      @click="$emit('confirmReceipt', order)"
    >
      确认收货
    </button>
    <button
      v-if="!historical && !deleted && ['COMPLETED', 'CANCELLED'].includes(order.status)"
      type="button"
      class="order-consult order-delete"
      :disabled="busy || deletionBlocked"
      :aria-label="`删除订单 ${order.orderNo}`"
      :title="deletionBlocked ? '有售后正在处理，暂不能删除' : undefined"
      @click="$emit('deleteOrder', order)"
    >
      <Trash2
        :size="13"
        aria-hidden="true"
      />
      {{ deletionBlocked ? '售后处理中' : '删除订单' }}
    </button>
  </article>
</template>
