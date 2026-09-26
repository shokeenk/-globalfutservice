import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// jsdom does not lay anything out, so it does not scroll. The pages call scrollTo when
// moving between steps; without this every such test prints "Not implemented".
window.scrollTo = () => {}

// Each test starts from an empty document. Without this, a component rendered by one
// test is still in the DOM when the next test queries it, and `getByRole` finds two.
afterEach(() => {
  cleanup()
})
