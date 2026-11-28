package com.strybok.kmp

/**
 * Links without an API call: the `parameterLinks` option of [Strybok].
 *
 * The owner of an application can let addresses of its link hosts open a route of the app
 * without creating a link first: **path links** such as `https://my-app.strybok.link/product/42?id=7`
 * and **Firebase long links** `https://my-app.strybok.link/?link=…&apn=…&ibi=…`. With this option
 * the SDK recognises those URLs as well and asks the service about them; the service decides
 * whether the route is allowed and answers with the route and payload it read, which is what the
 * app receives ([ResolvedDeepLink.routePath] and [ResolvedDeepLink.payload], never the tapped
 * path). Without the option the SDK accepts single-slug links only and sends exactly what 0.4.0
 * sent.
 *
 * [payloadKeys] are the query keys whose values travel with such a link, in the owner's order:
 * copy `parameterLinks.payloadKeys` from the integration guide of the application
 * (`GET /v1/apps/:appId/integration`, shown on the dashboard's setup screen). That value is
 * `null` while both kinds are switched off; then do not set the option. The keys are compiled
 * into the app: a key the owner adds later reaches users with the next app release, and the
 * service ignores keys it no longer allows.
 *
 * Of a tapped URL the SDK then sends its path as received, the first value of each of these keys
 * and the five `utm_*` values — for a long link the deep link's scheme, host and path with its
 * values of these keys, and `apn` and `ibi`. Nothing else of the query, and never the fragment.
 * Do not configure keys that carry personal data.
 *
 * @param payloadKeys at most 20 keys, each once, each 1–40 characters of `A–Z a–z 0–9 _ . -`, not
 *   starting with `utm_` in any case and not `st`, `sd`, `si` or `strybok_click` (compared
 *   exactly: `ST` is an ordinary key). Empty is allowed: path links then carry no payload.
 * @throws IllegalArgumentException when [payloadKeys] breaks these rules, like an invalid
 *   `apiBaseUrl` of [Strybok]. The keys of the integration guide always pass.
 */
public class ParameterLinks(payloadKeys: List<String>) {
    /** The keys passed in, in their order. */
    public val payloadKeys: List<String> = payloadKeys.toList()

    init {
        require(this.payloadKeys.size <= MAX_PAYLOAD_KEYS) {
            "parameterLinks: payloadKeys has ${this.payloadKeys.size} keys, at most $MAX_PAYLOAD_KEYS are allowed"
        }
        val seen = mutableSetOf<String>()
        for (key in this.payloadKeys) {
            require(key.length in 1..MAX_KEY_LENGTH && key.all(::isKeyChar)) {
                "parameterLinks: the payload key \"$key\" is not 1–$MAX_KEY_LENGTH letters, digits, \"_\", \".\" or \"-\""
            }
            require(!key.startsWith("utm_", ignoreCase = true) && key !in RESERVED_KEYS) {
                "parameterLinks: the payload key \"$key\" is read by Strybok itself (utm_*, st, sd, si, strybok_click)"
            }
            require(seen.add(key)) { "parameterLinks: the payload key \"$key\" appears twice" }
        }
    }

    override fun equals(other: Any?): Boolean = other is ParameterLinks && other.payloadKeys == payloadKeys

    override fun hashCode(): Int = payloadKeys.hashCode()

    override fun toString(): String = "ParameterLinks(payloadKeys: $payloadKeys)"

    private companion object {
        const val MAX_PAYLOAD_KEYS = 20
        const val MAX_KEY_LENGTH = 40

        /** Keys Strybok reads itself. */
        val RESERVED_KEYS = setOf("st", "sd", "si", "strybok_click")

        fun isKeyChar(char: Char): Boolean =
            char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '_' || char == '.' || char == '-'
    }
}
