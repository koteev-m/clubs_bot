import { defineConfig } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
export default defineConfig({
  testDir: './tests',
  testMatch: [
    'owner-manager-p2.spec.ts',
    'owner-manager-calendar.spec.ts',
    'owner-manager-unknown.spec.ts',
  ],
  outputDir: join(tmpdir(), 'clubs-owner-manager-p2-' + randomUUID()),
  use: {
    baseURL: 'http://127.0.0.1:5184',
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
  },
  projects: [
    { name: 'chromium', use: { browserName: 'chromium' } },
    { name: 'webkit', use: { browserName: 'webkit' } },
  ],
  webServer: {
    command: 'node tests/serve-onboarding-dist.mjs',
    env: { CLB_BROWSER_PORT: '5184' },
    url: 'http://127.0.0.1:5184/app?mode=client',
    reuseExistingServer: false,
  },
});
