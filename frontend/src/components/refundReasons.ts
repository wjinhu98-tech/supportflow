/** Shared selection data; the persisted reason includes both the category and its detail. */
export const REFUND_REASON_GROUPS = [
  {
    code: 'PERSONAL',
    label: '个人原因',
    options: [
      { code: 'NO_LONGER_NEEDED', label: '不想要了' },
      { code: 'WRONG_OR_DUPLICATE_PURCHASE', label: '买错、多买或重复下单' },
      { code: 'UNSUITABLE_SPECIFICATION', label: '尺寸或规格不合适' },
      { code: 'WRONG_DELIVERY_INFORMATION', label: '收货信息填写错误' },
    ],
  },
  {
    code: 'MERCHANT',
    label: '商铺原因',
    options: [
      { code: 'DAMAGED_OR_STAINED', label: '商品破损或有污渍' },
      { code: 'QUALITY_ISSUE', label: '商品质量问题' },
      { code: 'NOT_AS_DESCRIBED', label: '商品与描述不符' },
      { code: 'WRONG_ITEM', label: '发错商品' },
      { code: 'MISSING_ITEMS', label: '漏发或少发商品' },
      { code: 'LATE_SHIPMENT', label: '未按约定时间发货' },
    ],
  },
] as const

export type RefundReasonCategory = (typeof REFUND_REASON_GROUPS)[number]['code'] | 'OTHER'
export const OTHER_REASON_PREFIX = '其他：'
export const MAX_REFUND_REASON_LENGTH = 500
export const MAX_OTHER_REASON_LENGTH = MAX_REFUND_REASON_LENGTH - OTHER_REASON_PREFIX.length

export function formatRefundReason(
  category: RefundReasonCategory | '',
  detailCode: string,
  other: string,
): string {
  if (category === 'OTHER') {
    const text = other.trim()
    return text && other.length <= MAX_OTHER_REASON_LENGTH ? OTHER_REASON_PREFIX + text : ''
  }
  const group = REFUND_REASON_GROUPS.find((item) => item.code === category)
  const detail = group?.options.find((item) => item.code === detailCode)
  return group && detail ? `${group.label}：${detail.label}` : ''
}
