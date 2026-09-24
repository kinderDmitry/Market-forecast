#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

echo "[1/6] Structure"
test -f settings.gradle.kts
test -f app/build.gradle.kts
test -f app/src/main/java/MainActivity.kt
test -f app/src/main/java/AnalyticsEngine.kt

echo "[2/6] Version"
grep -q 'versionCode = 161' app/build.gradle.kts
grep -q 'versionName = "4.8.122"' app/build.gradle.kts

echo "[3/6] Search + streaming scanner architecture"
test -f app/src/main/java/ScannerEngine.kt
grep -q "class ScannerEngine" app/src/main/java/ScannerEngine.kt
grep -q "Universe.ALL" app/src/main/java/ScannerEngine.kt
grep -q "class ScannerForegroundService" app/src/main/java/ScannerForegroundService.kt
grep -q 'foregroundServiceType="dataSync"' app/src/main/AndroidManifest.xml
! grep -q "scanner_priority_active" app/src/main/java/MarketMonitorWorker.kt

echo "[4/6] No duplicate FavoriteQuote"
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

echo "[9/9] BCS API-only architecture"
test ! -e app/src/main/java/CatalogRefreshService.kt
grep -q "ScannerForegroundService" app/src/main/java/ScannerForegroundService.kt
grep -q "KEY_RESULTS" app/src/main/java/ScannerForegroundService.kt
grep -q "TRADING_INSTRUMENT_TYPES" app/src/main/java/MarketRepository.kt

echo "VERIFY_PROX: OK"
