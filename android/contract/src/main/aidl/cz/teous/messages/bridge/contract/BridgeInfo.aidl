// SPDX-License-Identifier: MIT
// Part of the Messages Bridge contract (docs/CONTRACT.md). Append only: never reorder, remove or retype a field.
package cz.teous.messages.bridge.contract;

/** What info() returns: which contract this bridge speaks, and the oldest Messages it still serves. */
parcelable BridgeInfo {
    /** IBridge.VERSION of the bridge. A client calls a method only when this is at least the version that added it. */
    int contractVersion;
    /** The oldest client contract version the bridge still serves; an older Messages should ask for an update. */
    int minClientVersion;
    @nullable String appVersion;
    long appVersionCode;
}
