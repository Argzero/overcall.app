import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
// Wallet-adapter pulls Buffer + a few node modules. The polyfill plugin
// surfaces them as ESM globals at build time.
export default defineConfig({
    plugins: [react()],
    define: {
        "process.env": {},
        global: "globalThis",
    },
    resolve: {
        alias: {
            buffer: "buffer/",
        },
    },
    optimizeDeps: {
        include: ["buffer"],
    },
});
