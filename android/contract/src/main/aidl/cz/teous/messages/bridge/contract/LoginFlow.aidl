// SPDX-License-Identifier: MIT
// Part of the Messages Bridge contract (docs/CONTRACT.md). Append only: never reorder, remove or retype a field.
package cz.teous.messages.bridge.contract;

/** One way to log in to a network, as bridgev2's provisioning flows: WhatsApp has "phone" and "qr". */
parcelable LoginFlow {
    String id;
    String name;
    @nullable String description;
}
