import { defineConfig } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
export default defineConfig({
  testDir: './tests',
  testMatch: 'owner-manager-integrated.spec.ts',
  outputDir: join(tmpdir(), 'clubs-owner-manager-' + randomUUID()),
  workers: 1,
  use: {
    baseURL: process.env.CLB_ONBOARDING_BASE_URL || 'http://127.0.0.1:54863',
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
  },
  projects: [
    { name: 'chromium', use: { browserName: 'chromium' } },
    { name: 'webkit', use: { browserName: 'webkit' } },
  ],
});
