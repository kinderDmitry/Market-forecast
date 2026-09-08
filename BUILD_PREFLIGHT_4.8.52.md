# Market Forecast PRO X 4.8.52 — preflight

- Version: 4.8.52 / versionCode 94.
- BCS is fail-closed for forecast candles and live quotes: no silent MOEX/Yahoo fallback.
- Candle/quote caches are separated by data-source mode, preventing stale pre-BCS values from leaking into BCS mode.
- Analytics Center multi-timeframe forecasts use a BCS-configured repository instead of a fresh provider without credentials.
- BCS source badge is shown on the Analytics Center and forecast header.
- Screenshot scanner UI and ScreenshotForecastEngine were removed.
- Ordinary scanner has no horizon/unit controls in its UI.
- Scanner results are persisted as soon as each signal is confirmed; the UI polls the result set every second.
- New scanner signals trigger notification immediately when the individual analysis completes.
- Scanner cards include an "Отследить" action.
- Expired scanner signals are filtered from the UI by expiresAt.
- Notifications use direction-aware colors: LONG green, SHORT red, NO TRADE yellow, TP green, SL red.
- GitHub Actions workflow checks version 4.8.52 and absence of ScreenshotForecastEngine.kt.

## Build note

The supplied source archive does not contain a Gradle wrapper and this execution environment does not provide the Android SDK/Gradle installation required for a full `:app:assembleDebug` build. Therefore this preflight report is static/source verification, not a claim of a completed remote Gradle build.
