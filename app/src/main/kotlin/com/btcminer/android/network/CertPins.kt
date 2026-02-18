package com.btcminer.android.network

/**
 * SPKI certificate pins for TLS pinning. Pins are SHA-256 of the DER-encoded Subject Public Key Info (base64).
 * Use backup pins so cert key rotation doesn't break the app until a release with the new backup is shipped.
 *
 * Update pins when the server rotates keys: run `./scripts/verify_cert_pins.sh` (or see its output) to get
 * the current pin, then add as backup and release.
 */
object CertPins {
    /** mempool.space - current and backup pins. CI verifies the live pin matches one of these (see scripts/verify_cert_pins.sh). */
    val MEMPOOL_SPACE_PINS = listOf(
        "sha256/wV7micOM/PJtIxPpaZBTdQF0JnfIHXSGzrvsu7fzDdQ=",  // Replace: run scripts/verify_cert_pins.sh and update
    )

    /** In WSL > Linux Bash > ./<project folder> :
    *   chmod +x verify_cert_pins.sh\
    *   ./scripts/verify_cert_pins.sh --print
    */


    /** True if mempool.space pins are configured (no placeholder); use pinning only then. */
    fun hasMempoolSpacePins(): Boolean = MEMPOOL_SPACE_PINS.none { it.contains("PLACEHOLDER") }
}
