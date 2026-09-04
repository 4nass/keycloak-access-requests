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
