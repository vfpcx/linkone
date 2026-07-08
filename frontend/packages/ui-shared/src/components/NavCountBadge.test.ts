import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import NavCountBadge from './NavCountBadge.vue'

describe('NavCountBadge', () => {
  it('renders the count', () => {
    const wrapper = mount(NavCountBadge, { props: { count: 6 } })
    expect(wrapper.text()).toBe('6')
    expect(wrapper.classes()).toContain('cc-navcount')
  })

  it('renders nothing when count <= 0', () => {
    expect(mount(NavCountBadge, { props: { count: 0 } }).find('.cc-navcount').exists()).toBe(false)
    expect(mount(NavCountBadge, { props: { count: -3 } }).find('.cc-navcount').exists()).toBe(false)
  })

  it('shows `99+` when count > max (default 99)', () => {
    const wrapper = mount(NavCountBadge, { props: { count: 120 } })
    expect(wrapper.text()).toBe('99+')
  })

  it('honours a custom max', () => {
    const wrapper = mount(NavCountBadge, { props: { count: 15, max: 9 } })
    expect(wrapper.text()).toBe('9+')
  })

  it('is a static inline element, not an absolutely-positioned superscript', () => {
    const wrapper = mount(NavCountBadge, { props: { count: 6 } })
    // Root is a single <span>, not an el-badge wrapper with a floating .is-fixed child.
    expect(wrapper.element.tagName).toBe('SPAN')
    expect(wrapper.find('.is-fixed').exists()).toBe(false)
    expect(wrapper.find('.el-badge__content').exists()).toBe(false)
  })
})
