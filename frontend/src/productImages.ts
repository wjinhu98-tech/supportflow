/** Order snapshots may only reference the locally served demo product image directory. */
export function isOrderProductImageUrl(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    value.length <= 240 &&
    value.trim() === value &&
    /^\/products\/demo\/[a-z0-9_-]+\.(?:png|webp|jpg|jpeg)$/.test(value)
  )
}
