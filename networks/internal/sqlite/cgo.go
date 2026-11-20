//go:build cgosqlite

package sqlite

import (
	"database/sql"
	"fmt"

	_ "github.com/mattn/go-sqlite3"
)

// Open with mattn's cgo SQLite, built only to compare its size with modernc's (Messages docs/spikes/wa-phone-bridge.md).
func Open(path string) (*sql.DB, error) {
	db, err := sql.Open("sqlite3", "file:"+path+"?_foreign_keys=on&_journal_mode=WAL&_busy_timeout=5000")
	if err != nil {
		return nil, fmt.Errorf("open store: %w", err)
	}
	return db, nil
}
