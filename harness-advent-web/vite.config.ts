import { defineConfig } from "vite";

// В development запросы остаются same-origin для браузера; Ktor пока не публикует CORS.
export default defineConfig({
  server: {
    proxy: {
      "/api": "http://127.0.0.1:8080",
      "/health": "http://127.0.0.1:8080",
    },
  },
});
