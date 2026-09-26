import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// Each test starts from an empty document. Without this, a component rendered by one
// test is still in the DOM when the next test queries it, and `getByRole` finds two.
afterEach(() => {
  cleanup()
})
