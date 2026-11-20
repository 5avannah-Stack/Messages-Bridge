package signalbridge

import (
	"os"
	"strings"
	"testing"

	"go.mau.fi/mautrix-signal/pkg/libsignalgo"
)

// libsignal.sh builds the libsignal that libsignalgo was generated against; a newer mautrix-signal needs its version
// here too, or the link fails on a missing signal_ffi_version symbol.
func TestLibsignalVersion(t *testing.T) {
	data, err := os.ReadFile("libsignal.version")
	if err != nil {
		t.Fatal(err)
	}
	if got := strings.TrimSpace(string(data)); got != libsignalgo.Version {
		t.Fatalf("libsignal.version is %s, mautrix-signal needs %s", got, libsignalgo.Version)
	}
}
