#!/usr/bin/env bash
# Verify that the live certificate pin for mempool.space matches one of the pins in CertPins.kt.
# Run from repo root. Requires openssl.
# Usage:
#   ./scripts/verify_cert_pins.sh          - fail build if pin mismatch
#   ./scripts/verify_cert_pins.sh --print  - print current pin (to update CertPins.kt)
set -e
HOST="${1:-mempool.space}"
PRINT_ONLY="${2:-}"
CERT_PINS_FILE="app/src/main/kotlin/com/btcminer/android/network/CertPins.kt"
if [ "$1" = "--print" ]; then
  PRINT_ONLY=1
  HOST="mempool.space"
fi

get_pin() {
  local host="$1"
  echo | openssl s_client -connect "$host:443" -servername "$host" 2>/dev/null | \
    openssl x509 -pubkey -noout | \
    openssl pkey -pubin -outform DER | \
    openssl dgst -sha256 -binary | \
    openssl enc -base64 | tr -d '\n'
}

PIN="sha256/$(get_pin "$HOST")"
if [ -n "$PRINT_ONLY" ]; then
  echo "Current pin for $HOST: $PIN"
  echo "Add to CertPins.MEMPOOL_SPACE_PINS in $CERT_PINS_FILE (and remove PLACEHOLDER if present)."
  exit 0
fi

if [ ! -f "$CERT_PINS_FILE" ]; then
  echo "Error: $CERT_PINS_FILE not found. Run from repo root." >&2
  exit 1
fi
if grep -q "PLACEHOLDER_MEMPOOL_SPACE_PIN" "$CERT_PINS_FILE"; then
  echo "Error: CertPins.kt still contains placeholder. Run: ./scripts/verify_cert_pins.sh --print" >&2
  exit 1
fi
if grep -Fq "$PIN" "$CERT_PINS_FILE"; then
  echo "OK: Live pin for $HOST matches CertPins.kt"
  exit 0
fi
echo "Error: Live pin for $HOST does not match any pin in $CERT_PINS_FILE" >&2
echo "Live pin: $PIN" >&2
echo "Run: ./scripts/verify_cert_pins.sh --print to get the pin, then add it to MEMPOOL_SPACE_PINS." >&2
exit 1
