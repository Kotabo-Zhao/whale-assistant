import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

afterEach(cleanup)

// Most UI fixtures run on a Chinese-language device. Locale-specific cases override this getter.
Object.defineProperty(window.navigator, 'languages', { configurable: true, get: () => ['zh-CN'] })
