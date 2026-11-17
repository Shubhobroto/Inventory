/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  // Relative asset paths, so the build works from any host sub-path.
  base: "./",
  plugins: [react(), tailwindcss()],
  test: {
    environment: "node",
  },
});
