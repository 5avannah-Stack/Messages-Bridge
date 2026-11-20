//go:build !cgosqlite

// Package sqlite opens the networks' stores, the same way for each.
package sqlite

import (
	"database/sql"
	"fmt"

	_ "modernc.org/sqlite"
)

// Open opens a store with modernc's pure-Go SQLite, the default: no C toolchain beyond gomobile's own, and the same
// code on every ABI. `-tags cgosqlite` swaps in the C build instead (cgo.go), only to compare sizes. whatsmeow refuses
// a store without foreign keys; WAL lets reads run while a sync writes.
func Open(path string) (*sql.DB, error) {
	db, err := sql.Open("sqlite", "file:"+path+"?_pragma=foreign_keys(1)&_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)")
	if err != nil {
		return nil, fmt.Errorf("open store: %w", err)
	}
	return db, nil
}
