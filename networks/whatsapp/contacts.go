package whatsapp

import (
	"context"
	"encoding/json"
	"sort"

	"go.mau.fi/whatsmeow/types"
)

type contact struct {
	JID      string `json:"jid"`
	Phone    string `json:"phone,omitempty"`
	Name     string `json:"name"`
	PushName string `json:"pushName,omitempty"`
}

// Contacts returns the person's WhatsApp contacts as a JSON array, sorted by name. Phone is how Messages links them
// to a contact (Messages DECISIONS 39); people WhatsApp only knows by LID have it only once WhatsApp shares the mapping.
func (b *Bridge) Contacts() (string, error) {
	if !b.IsPaired() {
		return "[]", nil
	}
	ctx := context.Background()
	all, err := b.cli.Store.Contacts.GetAllContacts(ctx)
	if err != nil {
		return "", err
	}
	contacts := make([]contact, 0, len(all))
	for jid, info := range all {
		contacts = append(contacts, contact{JID: jid.String(), Phone: b.phoneOf(ctx, jid), Name: displayName(info), PushName: info.PushName})
	}
	sort.Slice(contacts, func(i, j int) bool { return contacts[i].Name < contacts[j].Name })
	data, err := json.Marshal(contacts)
	return string(data), err
}

// nameOf is a person's name from the store's contacts, or "".
func (b *Bridge) nameOf(jid types.JID) string {
	if !b.IsPaired() || jid.Server == types.GroupServer {
		return ""
	}
	info, err := b.cli.Store.Contacts.GetContact(context.Background(), jid)
	if err != nil {
		return ""
	}
	return displayName(info)
}

// phoneOf gives a person's number: the JID itself for phone-number JIDs, the stored mapping for LIDs.
func (b *Bridge) phoneOf(ctx context.Context, jid types.JID) string {
	switch jid.Server {
	case types.DefaultUserServer:
		return jid.User
	case types.HiddenUserServer:
		if !b.IsPaired() {
			return ""
		}
		if pn, err := b.cli.Store.LIDs.GetPNForLID(ctx, jid); err == nil && !pn.IsEmpty() {
			return pn.User
		}
	}
	return ""
}

func displayName(info types.ContactInfo) string {
	for _, name := range []string{info.FullName, info.FirstName, info.BusinessName, info.PushName} {
		if name != "" {
			return name
		}
	}
	return ""
}
