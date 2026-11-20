// SPDX-License-Identifier: MIT
// Part of the Messages Bridge contract (docs/CONTRACT.md). Append only: never reorder, remove or retype a field.
package cz.teous.messages.bridge.contract;

import cz.teous.messages.bridge.contract.CookieField;
import cz.teous.messages.bridge.contract.LoginField;

/**
 * Where a login stands, modelled on bridgev2's login steps. Messages shows the step, then calls submitLogin() for
 * USER_INPUT and COOKIES. A DISPLAY step changes by itself (a QR code renews, a code is entered on the other phone):
 * the next step arrives as an Event of TYPE_LOGIN with the same loginId.
 */
parcelable LoginStep {
    const int TYPE_USER_INPUT = 1;
    const int TYPE_COOKIES = 2;
    const int TYPE_DISPLAY = 3;
    const int TYPE_COMPLETE = 4;
    const int TYPE_FAILED = 5;

    const int DISPLAY_QR = 1;       // displayData is the QR code's text; draw it as a QR code
    const int DISPLAY_CODE = 2;     // displayData is a code to type on the other device, such as WhatsApp's XXXX-XXXX
    const int DISPLAY_NOTHING = 3;  // only the instructions; wait

    int type;
    String loginId;
    String stepId;
    String network;
    @nullable String instructions;

    // TYPE_USER_INPUT
    @nullable LoginField[] fields;

    // TYPE_COOKIES: open url in a WebView with userAgent, collect cookieFields, run extractJs if set.
    @nullable String url;
    @nullable String userAgent;
    @nullable CookieField[] cookieFields;
    @nullable String extractJs;

    // TYPE_DISPLAY
    int displayType;
    @nullable String displayData;
    /** When the display stops working, in epoch ms, or 0. */
    long expiresAtMs;

    // TYPE_COMPLETE: the account now logged in. TYPE_FAILED: why, for the person to read.
    @nullable String accountId;
    @nullable String error;

    // Since contract version 2. TYPE_COOKIES: a regular expression the page's URL matches once the person is logged in;
    // the cookies go to submitLogin only then, even if some of them are set earlier. Null: as soon as the required
    // ones are there.
    @nullable String waitForUrl;
}
