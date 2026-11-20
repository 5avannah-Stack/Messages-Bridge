// SPDX-License-Identifier: MIT
// Part of the Messages Bridge contract (docs/CONTRACT.md). Append only: never reorder, remove or retype a field.
package cz.teous.messages.bridge.contract;

/** One field a USER_INPUT login step asks for. Messages draws it; the bridge checks it. */
parcelable LoginField {
    const int TYPE_TEXT = 1;
    const int TYPE_PHONE = 2;       // international, digits only once submitted, such as 10005550100
    const int TYPE_PASSWORD = 3;
    const int TYPE_CODE = 4;        // a code sent by the network, such as an SMS code
    const int TYPE_EMAIL = 5;
    const int TYPE_USERNAME = 6;

    int type;
    /** The key of this field's value in submitLogin()'s Bundle. */
    String id;
    String name;
    @nullable String description;
    /** A Java regular expression the value must match, when the bridge knows one. */
    @nullable String pattern;
    @nullable String defaultValue;
}
