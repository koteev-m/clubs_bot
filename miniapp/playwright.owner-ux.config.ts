import { defineConfig } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

export default defineConfig({
  testDir: './tests',
  testMatch: [
    'owner-ux-map.spec.ts',
    'owner-map-preview.spec.ts',
    'client-calendar.spec.ts',
    'client-booking-rules.spec.ts',
    'client-guest-booking-preview.spec.ts',
  ],
  outputDir: join(tmpdir(), `clubs-owner-ux-${randomUUID()}`),
  use: {
    baseURL: 'http://127.0.0.1:5183',
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
    url: 'http://127.0.0.1:5183/app?mode=client',
    reuseExistingServer: false,
  },
});
