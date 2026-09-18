#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

echo "[1/6] Structure"
test -f settings.gradle.kts
test -f app/build.gradle.kts
test -f app/src/main/java/MainActivity.kt
test -f app/src/main/java/ScannerForegroundService.kt
test -f app/src/main/java/AnalyticsEngine.kt

echo "[2/6] Version"
grep -q 'versionCode = 130' app/build.gradle.kts
grep -q 'versionName = "4.8.91"' app/build.gradle.kts

echo "[3/6] Scanner/forecast consistency"
grep -q 'fun analyzeForScanner' app/src/main/java/AnalyticsEngine.kt
grep -A4 'fun analyzeForScanner' app/src/main/java/AnalyticsEngine.kt | grep -q 'analyze(c, entryOverride)'
grep -q 'AnalyticsEngine.analyzeForScanner' app/src/main/java/ScannerForegroundService.kt

echo "[4/6] Scanner service contract"
grep -q 'override fun onBind(intent: Intent?): IBinder? = null' app/src/main/java/ScannerForegroundService.kt
grep -q 'const val ACTION_START' app/src/main/java/ScannerForegroundService.kt
grep -q 'const val ACTION_STOP' app/src/main/java/ScannerForegroundService.kt
grep -q 'const val EXTRA_SCOPE' app/src/main/java/ScannerForegroundService.kt
grep -q 'const val EXTRA_TYPE' app/src/main/java/ScannerForegroundService.kt
grep -q 'const val EXTRA_TF' app/src/main/java/ScannerForegroundService.kt

echo "[5/6] No duplicate FavoriteQuote"
COUNT=$(grep -R -h -c 'data class FavoriteQuote' app/src/main/java --include='*.kt' | awk '{s+=$1} END{print s+0}')
test "$COUNT" -eq 1

echo "[6/6] Pure Kotlin analytics compile"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
kotlinc app/src/main/java/Models.kt app/src/main/java/AnalyticsEngine.kt -d "$TMP/analytics.jar"

echo "[7/9] BCS API identity/rate-limit hardening"
grep -q 'classCode: String = ""' app/src/main/java/Models.kt
grep -q 'BCS_MIN_REQUEST_GAP_MS = 115L' app/src/main/java/MarketRepository.kt
grep -q 'contains("HTTP 429")' app/src/main/java/MarketRepository.kt
grep -q '"циан" to "CNRU"' app/src/main/java/MarketRepository.kt

echo "[8/9] No stale live-price fallback"
! grep -q 'stableQuoteCache' app/src/main/java/MarketRepository.kt
! grep -q 'repo.quote(symbol) }.getOrNull() ?: candles.last().close' app/src/main/java/MarketMonitorWorker.kt

echo "[9/9] Scanner persists exact BCS classCode"
grep -q 'limit = 17' app/src/main/java/ScannerForegroundService.kt
grep -q 'it.result.classCode' app/src/main/java/ScannerForegroundService.kt
grep -q 'ALL_BCS_INSTRUMENT_TYPES' app/src/main/java/MarketRepository.kt
grep -q 'Int.MAX_VALUE' app/src/main/java/MarketRepository.kt
grep -q 'monotonically increasing index' app/src/main/java/ScannerForegroundService.kt
grep -q 'distinctBy { scanIdentity(it) }' app/src/main/java/ScannerForegroundService.kt

echo "VERIFY_PROX: OK"
