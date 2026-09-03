import react from "@vitejs/plugin-react-swc";
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
        sourcemap: true,
        target: "esnext",
        modulePreload: false,
        cssMinify: "lightningcss",
        rollupOptions: {
            input: "src/main.tsx",
            external: ["react", "react/jsx-runtime", "react-dom"]
        }
    },
    test: {
        environment: "jsdom",
        include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
        setupFiles: "./src/test/setup.ts"
    }
});
