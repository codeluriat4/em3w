package org.example.test.bitget

/**
 * A Bitget **Demo API Key** triple (created while the account is switched to
 * Demo mode in the Bitget app: Personal Center -> API Key Management ->
 * Create Demo API Key). These keys can only touch the demo/paper-trading
 * balance - Bitget rejects them for live trading - but they are still
 * secrets and shouldn't be logged or committed anywhere.
 */
data class BitgetCredentials(
    val apiKey: String,
    val secretKey: String,
    val passphrase: String,
    // true routes requests at Bitget's demo/sandbox matching engine (the
    // `paptrading` header) instead of the real one - see the "Environment"
    // selector on the live API Key dialog.
    val isTestnet: Boolean = false,
) {
    // Passphrase is optional: most Bitget API Keys require one, but not all
    // exchanges/keys do, so we don't hard-block saving without it.
    val isComplete: Boolean
        get() = apiKey.isNotBlank() && secretKey.isNotBlank()
}
