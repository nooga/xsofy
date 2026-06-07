const { defineConfig, devices } = require('@playwright/test');
module.exports = defineConfig({
  testDir: '.',
  timeout: 90000,
  expect: { timeout: 20000 },
  fullyParallel: false,
  workers: 1,
  retries: 1,
  reporter: [['list']],
  use: { ...devices['Desktop Chrome'], baseURL: 'http://localhost:8123' },
});
