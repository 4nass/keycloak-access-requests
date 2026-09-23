import react from "@vitejs/plugin-react-swc";
import { defineConfig } from "vitest/config";

export default defineConfig(({ mode }) => {
    const consoleName = mode === "admin" ? "admin" : "account";
    const port = consoleName === "admin" ? 5174 : 5173;

    return {
        plugins: [react()],
        server: {
            origin: `http://localhost:${port}`,
            port
        },
        base: "",
        build: {
            // Vite's raw minified chunk size is not the shipped transfer budget. The Admin
            // theme intentionally includes Keycloak's complete Admin UI shell; the packaged
            // Playwright suite enforces the real compressed/uncompressed browser transfer budget.
            chunkSizeWarningLimit: consoleName === "admin" ? 3072 : 1024,
            outDir: `dist/${consoleName}`,
            manifest: true,
            sourcemap: true,
            target: "esnext",
            modulePreload: false,
            cssMinify: "lightningcss",
            rollupOptions: {
                input: `src/${consoleName}/main.tsx`,
                external: ["react", "react/jsx-runtime", "react-dom"]
            }
        },
        test: {
            environment: "jsdom",
            include: [`src/${consoleName}/**/*.test.ts`, `src/${consoleName}/**/*.test.tsx`],
            setupFiles: `./src/${consoleName}/test/setup.ts`
        }
    };
});
