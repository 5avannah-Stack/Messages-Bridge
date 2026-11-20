// SPDX-License-Identifier: MIT
// Part of the Messages Bridge contract (docs/CONTRACT.md). Append only: never reorder, remove or retype a field.
package cz.teous.messages.bridge.contract;

/**
 * One value a COOKIES login step needs from the WebView Messages opens: a cookie, a local-storage key or a request
 * header seen while the person logs in on the network's own page, as bridgev2's cookie login.
 */
parcelable CookieField {
    const int SOURCE_COOKIE = 1;
    const int SOURCE_LOCAL_STORAGE = 2;
    const int SOURCE_REQUEST_HEADER = 3;

    /** The key of this value in submitLogin()'s Bundle. */
    String id;
    boolean required;
    int source;
    /** The cookie, key or header name. */
    String name;
    /** For SOURCE_COOKIE, the domain to read it on. */
    @nullable String domain;
}
