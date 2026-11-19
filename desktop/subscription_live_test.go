package main

import (
	"encoding/json"
	"os"
	"testing"
	"time"
)

func TestSubscriptionLiveRefresh(t *testing.T) {
	if os.Getenv("LITE404_LIVE_SUBSCRIPTION") != "1" {
		t.Skip("explicit live account check")
	}
	s := loadSettings()
	token, err := ParseToken(s.Token)
	if err != nil {
		t.Fatal("saved account unavailable")
	}
	var cached AppConfig
	json.Unmarshal(s.Config, &cached)
	fresh, _, err := FetchConfig(token, hwid())
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("cached expiry: %s; current API expiry: %s; paid until: %s; queued periods: %d; API status: %s",
		time.Unix(cached.User.ExpiresAt, 0).UTC().Format(time.RFC3339), time.Unix(fresh.User.ExpiresAt, 0).UTC().Format(time.RFC3339), time.Unix(fresh.User.PaidUntil, 0).UTC().Format(time.RFC3339), fresh.User.QueuedPeriods, fresh.Error)
}
