package signalbridge

import (
	"context"
	"errors"
	"net/http"
	"time"

	"github.com/coder/websocket"
	"go.mau.fi/mautrix-signal/pkg/signalmeow"
)

// Signal's linking is QR-only: the person scans the code with Signal on their phone (Settings › Linked devices). The
// server closes a provisioning socket after a minute, so a fresh code comes every 45 seconds, as Signal Desktop does,
// up to 20 times.
var (
	qrRefresh   = 45 * time.Second
	qrRefreshes = 20
)

// provision starts one provisioning socket; tests replace it. The channel gives the QR link first, then the linked
// device's data or an error.
var provision = func(ctx context.Context, b *Bridge) chan signalmeow.ProvisioningResponse {
	return signalmeow.PerformProvisioning(ctx, b.container, DeviceName, true)
}

// LoginQR starts linking and returns the first link to show as a QR code (an sgnl://linkdevice URL). Each renewed
// link comes as a "qr" event; success as "paired"; giving up, a rejected link or an error as "qr_done". It offers the
// person's phone to transfer message history; whether it does is their choice there.
func (b *Bridge) LoginQR() (string, error) {
	if b.IsLinked() {
		return "", ErrAlreadyLinked
	}
	b.CancelLogin()
	ctx, cancel := context.WithCancel(context.Background())
	b.mu.Lock()
	b.loginCancel = cancel
	b.mu.Unlock()
	link, responses, attemptCancel, err := b.nextLink(ctx)
	if err != nil {
		b.CancelLogin()
		return "", err
	}
	go b.waitLink(ctx, responses, attemptCancel)
	return link, nil
}

// nextLink opens a provisioning socket and waits for its link.
func (b *Bridge) nextLink(ctx context.Context) (string, chan signalmeow.ProvisioningResponse, context.CancelFunc, error) {
	attempt, cancel := context.WithCancel(ctx)
	responses := provision(attempt, b)
	select {
	case r, ok := <-responses:
		if !ok {
			cancel()
			return "", nil, nil, errors.New("provisioning closed")
		}
		if r.Err != nil || r.State != signalmeow.StateProvisioningURLReceived {
			cancel()
			return "", nil, nil, linkError(r.Err)
		}
		return r.ProvisioningURL, responses, cancel, nil
	case <-ctx.Done():
		cancel()
		return "", nil, nil, ctx.Err()
	}
}

// waitLink waits for the person to scan, renewing the link until they do or it gives up.
func (b *Bridge) waitLink(ctx context.Context, responses chan signalmeow.ProvisioningResponse, attemptCancel context.CancelFunc) {
	defer b.CancelLogin()
	for renewed := 0; ; {
		select {
		case <-ctx.Done():
			attemptCancel()
			return
		case r, ok := <-responses:
			attemptCancel()
			if !ok || r.Err != nil || r.State != signalmeow.StateProvisioningDataReceived || r.ProvisioningData == nil {
				var err error
				if ok {
					err = r.Err
				}
				b.emit("qr_done", qrDone{Result: linkError(err).Error()})
				return
			}
			b.linked(r.ProvisioningData.ACI.String())
			return
		case <-time.After(qrRefresh):
			attemptCancel()
			if renewed++; renewed >= qrRefreshes {
				b.emit("qr_done", qrDone{Result: "timeout"})
				return
			}
			link, next, cancel, err := b.nextLink(ctx)
			if err != nil {
				if ctx.Err() == nil {
					b.emit("qr_done", qrDone{Result: err.Error()})
				}
				return
			}
			responses, attemptCancel = next, cancel
			b.emit("qr", qr{Code: link, TimeoutMs: qrRefresh.Milliseconds()})
		}
	}
}

// linked loads the device provisioning stored and reports it.
func (b *Bridge) linked(aci string) {
	ctx := context.Background()
	devices, err := b.container.GetAllDevices(ctx)
	if err != nil || len(devices) == 0 {
		b.emit("qr_done", qrDone{Result: "linked device missing from the store"})
		return
	}
	device := devices[0]
	for _, d := range devices {
		if d.ACI.String() == aci {
			device = d
		}
	}
	cli := b.newClient(device)
	// Contacts and the storage service bring names; the first connection asks the phone for them.
	cli.SyncContactsOnConnect = true
	b.mu.Lock()
	b.cli = cli
	b.mu.Unlock()
	b.emit("paired", paired{Account: device.ACI.String(), Number: digits(device.Number)})
}

// CancelLogin stops linking.
func (b *Bridge) CancelLogin() {
	b.mu.Lock()
	cancel := b.loginCancel
	b.loginCancel = nil
	b.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

// linkError names what went wrong in words the person can act on; Signal's statuses come from Signal-Server's
// DeviceController, as mautrix-signal reads them.
func linkError(err error) error {
	var linkErr signalmeow.DeviceLinkError
	switch {
	case err == nil:
		return errors.New("linking failed")
	case errors.As(err, &linkErr):
		switch linkErr.StatusCode {
		case http.StatusConflict:
			return errors.New("Signal needs a newer bridge to link this account")
		case http.StatusLengthRequired:
			return errors.New("the account already has the most linked devices Signal allows; remove one in Signal")
		case http.StatusForbidden:
			return errors.New("the code was invalid or already used; start again")
		case http.StatusTooManyRequests:
			return errors.New("Signal is limiting link attempts; wait a few minutes")
		}
		return errors.New("Signal rejected the link")
	case websocket.CloseStatus(err) == websocket.StatusGoingAway, errors.Is(err, context.DeadlineExceeded):
		return errors.New("timeout")
	}
	return err
}
