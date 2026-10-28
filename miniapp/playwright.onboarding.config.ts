import { defineConfig } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const external = process.env.CLB_ONBOARDING_BASE_URL;
export default defineConfig({
  testDir: './tests',
  testMatch: external
    ? 'onboarding-integrated.spec.ts'
    : 'onboarding-production.spec.ts',
  outputDir: join(tmpdir(), 'clubs-onboarding-playwright-' + randomUUID()),
  workers: 1,
  use: {
    baseURL: external || 'http://127.0.0.1:5183',
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
  },
  webServer: external
    ? undefined
    : {
        command: 'node tests/serve-onboarding-dist.mjs',
        port: 5183,
        reuseExistingServer: false,
      },
});
