import '@testing-library/jest-dom/vitest';

/**
 * Recharts sizes itself from the element it is given, and jsdom reports every element as
 * 0x0. Left alone, every chart renders at zero width and any assertion about it fails for
 * a reason that has nothing to do with the code under test.
 *
 * <p>Two things are needed. The layout properties have to report a size, and the
 * ResizeObserver has to actually deliver one — a stub that records the callback and never
 * calls it leaves the container waiting forever.
 */
const WIDTH = 800;
const HEIGHT = 400;

for (const property of ['clientWidth', 'offsetWidth'] as const) {
  Object.defineProperty(HTMLElement.prototype, property, { configurable: true, value: WIDTH });
}
for (const property of ['clientHeight', 'offsetHeight'] as const) {
  Object.defineProperty(HTMLElement.prototype, property, { configurable: true, value: HEIGHT });
}

HTMLElement.prototype.getBoundingClientRect = function getBoundingClientRect(): DOMRect {
  return {
    width: WIDTH, height: HEIGHT, top: 0, left: 0, bottom: HEIGHT, right: WIDTH, x: 0, y: 0,
    toJSON: () => ({}),
  } as DOMRect;
};

class SizedResizeObserver implements ResizeObserver {
  // A plain field, not a constructor parameter property: this project compiles with
  // erasableSyntaxOnly, which rules that syntax out.
  private readonly callback: ResizeObserverCallback;

  constructor(callback: ResizeObserverCallback) {
    this.callback = callback;
  }

  observe(target: Element): void {
    const contentRect = { width: WIDTH, height: HEIGHT, top: 0, left: 0, bottom: HEIGHT,
      right: WIDTH, x: 0, y: 0, toJSON: () => ({}) } as DOMRect;
    // Synchronously, so the first render already has a size to work with.
    this.callback(
      [{ target, contentRect, borderBoxSize: [], contentBoxSize: [], devicePixelContentBoxSize: [] }],
      this,
    );
  }

  unobserve(): void {}

  disconnect(): void {}
}

globalThis.ResizeObserver = SizedResizeObserver;

/**
 * jsdom implements no layout, so it has no scrolling either and `scrollIntoView` is
 * simply absent. Calling it is correct in a browser, so the component keeps the call and
 * the environment gets the no-op it is missing.
 */
HTMLElement.prototype.scrollIntoView = function scrollIntoView(): void {};
