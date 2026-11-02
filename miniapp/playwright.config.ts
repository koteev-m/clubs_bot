import { defineConfig } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

export default defineConfig({
  testDir: './tests',
  testIgnore: [
    'onboarding-integrated.spec.ts',
    'onboarding-production.spec.ts',
    // These suites exercise the packaged entry with playwright.owner-ux.config.ts.
    'owner-ux-map.spec.ts',
    'owner-map-preview.spec.ts',
  ],
  outputDir: join(
    tmpdir(),
    `clubs-bot-playwright-${process.pid}-${randomUUID()}`,
  ),
  webServer: {
    command: 'node node_modules/vite/bin/vite.js',
    port: 5173,
    reuseExistingServer: true,
  },
});
