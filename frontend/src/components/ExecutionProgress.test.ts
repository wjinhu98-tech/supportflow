import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { AfterSaleExecution } from '../types'
import ExecutionProgress from './ExecutionProgress.vue'

const execution: AfterSaleExecution = {
  status: 'SUCCEEDED',
  amount: 199.9,
  currency: 'CNY',
  attemptCount: 1,
  maxAttempts: 5,
  nextAttemptAt: null,
  completedAt: '2026-10-22T03:00:00Z',
  reference: 'SIM-TEST-REF',
  lastError: null,
  retryable: false,
}

describe('ExecutionProgress', () => {
  it('shows the server amount and simulated reference independently of approval', () => {
    const view = mount(ExecutionProgress, { props: { execution } })
    expect(view.text()).toContain('模拟退款成功')
    expect(view.text()).toContain('199.90')
    expect(view.get('[data-testid="execution-reference"]').text()).toBe('SIM-TEST-REF')
    expect(view.text()).toContain('不代表真实资金到账')
    expect(view.find('button').exists()).toBe(false)
  })

  it('offers an explicit retry only to reviewers for a retryable failed task', async () => {
    const failed: AfterSaleExecution = {
      ...execution,
      status: 'FAILED',
      completedAt: null,
      reference: null,
      lastError: '模拟渠道失败',
      retryable: true,
    }
    const view = mount(ExecutionProgress, { props: { execution: failed } })
    expect(view.find('button').exists()).toBe(false)
    await view.setProps({ canRetry: true })
    await view.get('[data-testid="retry-execution"]').trigger('click')
    expect(view.emitted('retry')).toEqual([[]])
    await view.setProps({ busy: true })
    expect(view.get('button').attributes('disabled')).toBeDefined()
    await view.setProps({
      execution: { ...failed, attemptCount: 5, retryable: false },
      busy: false,
    })
    expect(view.find('button').exists()).toBe(false)
    expect(view.text()).toContain('执行次数上限')
  })

  it('renders failure details as text instead of interpreting HTML', () => {
    const view = mount(ExecutionProgress, {
      props: {
        execution: {
          ...execution,
          status: 'FAILED',
          lastError: '<img src=x onerror=alert(1)>',
          retryable: true,
        },
      },
    })
    expect(view.find('img').exists()).toBe(false)
    expect(view.get('[data-testid="execution-error"]').text()).toContain('<img')
  })

  it('does not present approval without an execution result as a refund success', () => {
    const view = mount(ExecutionProgress, { props: { execution: null } })
    expect(view.text()).toContain('暂无执行任务')
    expect(view.text()).toContain('历史审批不会自动补执行')
    expect(view.text()).not.toContain('模拟退款成功')
  })

  it('distinguishes a permanent failure from exhausting the attempt limit', () => {
    const view = mount(ExecutionProgress, {
      props: { execution: { ...execution, status: 'FAILED', attemptCount: 1, retryable: false } },
    })
    expect(view.text()).toContain('当前任务不可重试')
    expect(view.text()).not.toContain('已达到执行次数上限')
  })
})
