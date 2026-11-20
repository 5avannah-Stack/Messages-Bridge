// SPDX-License-Identifier: MIT
// Part of the Messages Bridge contract (docs/CONTRACT.md). Append only: never reorder, remove or retype a field.
package cz.teous.messages.bridge.contract;

import cz.teous.messages.bridge.contract.Event;

/** Messages' callback. Events come in seq order; the same events stay readable through IBridge.events() until acked. */
oneway interface IBridgeListener {
    void onEvents(in Event[] events);
}
