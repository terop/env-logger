import js from "@eslint/js";
import globals from "globals";
import { defineConfig } from "eslint/config";

export default defineConfig([
  {
    files: ["**/*.{js,mjs,cjs}"],
    plugins: { js },
    extends: ["js/recommended"],
    languageOptions: { globals: globals.browser }
  },
  {
    files: [
      "src/public/js/chart/**/*.js",
      "src/public/js/auth-logic.js",
      "src/public/js/auth/**/*.js",
      "src/public/js/__tests__/**/*.js",
      "vitest.config.js"
    ],
    languageOptions: {
      sourceType: "module",
      globals: {
        ...globals.browser,
        ...globals.node
      }
    }
  },
  {
    files: ["src/public/js/auth-load.js"],
    languageOptions: { sourceType: "script" }
  },
  {
    ignores: ["src/public/js/chart.bundle.js", "src/public/js/auth.js"]
  }
]);
