import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/public/js/**/__tests__/**/*.test.js'],
    setupFiles: ['src/public/js/chart/__tests__/setup.js']
  }
});
