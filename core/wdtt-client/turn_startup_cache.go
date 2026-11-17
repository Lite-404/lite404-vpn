package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Only short-lived TURN leases that have relayed a successful handshake are
// saved. The Android app supplies a private directory excluded from backups.
type turnStartupFile struct {
	Version int                     `json:"version"`
	Scope   string                  `json:"scope"`
	Leases  map[int]TurnCredentials `json:"leases"`
}

type turnStartupStore struct {
	path string
	mu   sync.Mutex
	data turnStartupFile
}

var startupTurnStore *turnStartupStore

func restoreTurnStartupCache(path string, hashes []string, peer, device, network string) {
	if path == "" {
		return
	}
	key := turnStartupScope(hashes, peer, device, network, getVKAuthMode())
	startupTurnStore = openTurnStartupStore(path, key, time.Now())
	restored := 0
	for id, lease := range startupTurnStore.data.Leases {
		group := id/credentialStreamStride - 1
		if group < 0 || group >= 12 || id%credentialStreamStride != 0 {
			delete(startupTurnStore.data.Leases, id)
			continue
		}
		lease.Link = hashes[group%len(hashes)]
		getStreamCache(id).creds = lease
		restored++
	}
	log.Printf("[TURN] Готовых групп в кэше запуска: %d", restored)
}

func turnStartupScope(hashes []string, peer, device, network, authMode string) string {
	parts := append([]string{peer, device, network, authMode}, hashes...)
	sum := sha256.Sum256([]byte(strings.Join(parts, "\x00")))
	return hex.EncodeToString(sum[:])
}

func openTurnStartupStore(path, scope string, now time.Time) *turnStartupStore {
	store := &turnStartupStore{
		path: path,
		data: turnStartupFile{Version: 1, Scope: scope, Leases: make(map[int]TurnCredentials)},
	}
	file, err := os.Open(path)
	if err != nil {
		return store
	}
	defer file.Close()
	const maxCacheBytes = 64 << 10
	raw, err := io.ReadAll(io.LimitReader(file, maxCacheBytes+1))
	if err != nil || len(raw) > maxCacheBytes {
		return store
	}
	var saved turnStartupFile
	if json.Unmarshal(raw, &saved) != nil || saved.Version != store.data.Version || saved.Scope != scope {
		return store
	}
	for id, lease := range saved.Leases {
		if lease.Username != "" && lease.Password != "" && len(lease.ServerAddrs) > 0 &&
			now.Before(lease.ExpiresAt) && !lease.ExpiresAt.After(now.Add(credentialLifetime)) {
			store.data.Leases[id] = lease
		}
	}
	return store
}

func rememberUsableTurnCredentials(creds *Credentials) {
	store := startupTurnStore
	if store == nil {
		return
	}
	id := creds.CacheStreamID
	cache := getStreamCache(id)
	cache.mutex.RLock()
	defer cache.mutex.RUnlock()
	lease := cache.creds
	if lease.Username == creds.User && lease.Password == creds.Pass && time.Now().Before(lease.ExpiresAt) {
		store.remember(id, lease)
	}
}

func (s *turnStartupStore) remember(id int, lease TurnCredentials) {
	s.mu.Lock()
	defer s.mu.Unlock()
	saved, exists := s.data.Leases[id]
	if exists && saved.ExpiresAt.Equal(lease.ExpiresAt) && saved.Username == lease.Username && saved.Password == lease.Password {
		return
	}
	s.data.Leases[id] = lease
	s.save()
}

func (s *turnStartupStore) forget(id int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, exists := s.data.Leases[id]; exists {
		delete(s.data.Leases, id)
		s.save()
	}
}

func (s *turnStartupStore) save() {
	if err := writeTurnStartupFile(s.path, s.data); err != nil {
		log.Printf("[TURN] Не удалось сохранить кэш запуска: %v", err)
	}
}

func writeTurnStartupFile(path string, data turnStartupFile) error {
	if len(data.Leases) == 0 {
		if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		return nil
	}
	temp, err := os.CreateTemp(filepath.Dir(path), ".wdtt-turn-*")
	if err != nil {
		return err
	}
	defer os.Remove(temp.Name())
	writeErr := json.NewEncoder(temp).Encode(data)
	closeErr := temp.Close()
	if writeErr != nil || closeErr != nil {
		return errors.Join(writeErr, closeErr)
	}
	return os.Rename(temp.Name(), path)
}
