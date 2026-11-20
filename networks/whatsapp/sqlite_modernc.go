//go:build !cgosqlite

package whatsapp

import (
	"database/sql"
	"fmt"

	_ "modernc.org/sqlite"
)

// openDB opens the store with modernc's pure-Go SQLite, the default: no C toolchain beyond gomobile's own, and the
// same code on every ABI. `-tags cgosqlite` swaps in the C build instead (sqlite_cgo.go), only to compare sizes.
// whatsmeow refuses a store without foreign keys; WAL lets reads run while a sync writes.
func openDB(path string) (*sql.DB, error) {
	db, err := sql.Open("sqlite", "file:"+path+"?_pragma=foreign_keys(1)&_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)")
	if err != nil {
		return nil, fmt.Errorf("open store: %w", err)
	}
	return db, nil
}
