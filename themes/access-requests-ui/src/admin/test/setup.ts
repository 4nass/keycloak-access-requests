import { cleanup } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import { afterEach } from "vitest";

if (!i18n.isInitialized) {
    await i18n.use(initReactI18next).init({
        fallbackLng: "en",
        initImmediate: false,
        lng: "en",
        resources: { en: { translation: {} } }
    });
}

afterEach(cleanup);
