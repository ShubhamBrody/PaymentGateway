import path from 'node:path'
import { defineConfig, devices } from '@playwright/test'

const java = process.env.JAVA_HOME ? `"${path.join(process.env.JAVA_HOME, 'bin', 'java')}"` : 'java'
const backendJar = path.join('..', 'backend', 'target', 'payment-system-0.0.1-SNAPSHOT.jar')

// Needs Redis running (docker compose up -d redis) and the backend jar built (mvnw package).
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command: [
        java,
        '-jar',
        `"${backendJar}"`,
        '--spring.datasource.url=jdbc:h2:mem:playwright;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000',
        '--app.processing.bank-reply-timeout=1s',
        '--app.processing.maintenance-interval=200ms',
      ].join(' '),
      url: 'http://127.0.0.1:8080/actuator/health',
      reuseExistingServer: true,
      timeout: 120_000,
    },
    {
      command: 'npm run dev -- --strictPort',
      url: 'http://localhost:5173',
      reuseExistingServer: true,
      timeout: 60_000,
    },
  ],
})
