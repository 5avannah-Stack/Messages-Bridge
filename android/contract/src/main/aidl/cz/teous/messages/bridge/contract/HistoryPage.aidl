// SPDX-License-Identifier: MIT
// Part of the Messages Bridge contract (docs/CONTRACT.md). Append only: never reorder, remove or retype a field.
package cz.teous.messages.bridge.contract;

import cz.teous.messages.bridge.contract.Message;

/** One page of a chat's history, newest first. */
parcelable HistoryPage {
    Message[] messages;
    /** Older messages exist, here or on the network; ask again from the oldest one in this page. */
    boolean hasMore;
}
