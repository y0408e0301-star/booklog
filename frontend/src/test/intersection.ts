import { vi } from 'vitest'

// jsdom には IntersectionObserver がないので、テストから「見えた」ことにできる偽物に差し替える。
// 監視している要素がすべて見えたことにするには scrollToBottom() を呼ぶ。
// 画面が監視を始める（useEffect）前に呼ぶと何も起きないので、先に observing() が true になるのを待つ
export function mockIntersectionObserver() {
  const observers = new Set<FakeObserver>()

  class FakeObserver {
    readonly targets = new Set<Element>()
    readonly callback: IntersectionObserverCallback
    constructor(callback: IntersectionObserverCallback) {
      this.callback = callback
      observers.add(this)
    }
    observe(target: Element) {
      this.targets.add(target)
    }
    unobserve(target: Element) {
      this.targets.delete(target)
    }
    disconnect() {
      this.targets.clear()
      observers.delete(this)
    }
    takeRecords() {
      return []
    }
    fire() {
      const entries = [...this.targets].map((target) => ({ target, isIntersecting: true }) as IntersectionObserverEntry)
      if (entries.length > 0) this.callback(entries, this as unknown as IntersectionObserver)
    }
  }

  vi.stubGlobal('IntersectionObserver', FakeObserver)
  return {
    scrollToBottom: () => [...observers].forEach((o) => o.fire()),
    observing: () => [...observers].some((o) => o.targets.size > 0),
  }
}
