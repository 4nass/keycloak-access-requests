import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
    plugins: [react()],
    server: {
        origin: "http://localhost:5173",
        port: 5173
    },
    base: "",
    build: {
        manifest: true,
        rollupOptions: {
            input: "src/main.tsx",
            external: ["react", "react/jsx-runtime", "react-dom"]
        }
    },
    test: {
        environment: "jsdom",
        setupFiles: "./src/test/setup.ts"
    }
});
