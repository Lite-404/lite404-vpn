package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestTurnStartupCacheRetainsOnlyLiveLeasesForMatchingProfile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "turn.json")
	now := time.Now()
	scope := turnStartupScope([]string{"private-invite"}, "peer", "device", "wifi", "vkcalls")
	store := openTurnStartupStore(path, scope, now)
	lease := TurnCredentials{
		Username: "session", Password: "temporary", ServerAddrs: []string{"turn.example:3478"},
		ExpiresAt: now.Add(time.Minute), Link: "private-invite",
	}
	store.remember(100, lease)
	lease.ExpiresAt = now.Add(-time.Second)
	store.remember(200, lease)
	reloaded := openTurnStartupStore(path, scope, now)
	if len(reloaded.data.Leases) != 1 || reloaded.data.Leases[100].Username != "session" {
		t.Fatalf("expected exactly the unexpired lease")
	}
	raw, err := os.ReadFile(path)
	if err != nil || strings.Contains(string(raw), lease.Link) {
		t.Fatal("raw call link must stay out of the persisted cache")
	}
	mobile := turnStartupScope([]string{"private-invite"}, "peer", "device", "cell", "vkcalls")
	if len(openTurnStartupStore(path, mobile, now).data.Leases) != 0 {
		t.Fatal("a different underlying network must start with its own credentials")
	}
	reloaded.forget(100)
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("a rejected lease must be removed from disk")
	}
}
