package main

import (
	"testing"
	"time"
)

func TestNetworkChangeDebouncesAndRecovers(t *testing.T) {
	var n networkTracker
	now := time.Now()
	if n.changed("wifi", now) || n.changed("mobile", now) || n.changed("mobile", now.Add(time.Second)) {
		t.Fatal("restart before network settled")
	}
	if !n.changed("mobile", now.Add(2*time.Second)) || n.changed("mobile", now.Add(3*time.Second)) {
		t.Fatal("must restart exactly once")
	}
	if n.changed("", now.Add(4*time.Second)) || n.changed("", now.Add(7*time.Second)) {
		t.Fatal("must not restart while offline")
	}
	n.changed("mobile", now.Add(8*time.Second))
	if !n.changed("mobile", now.Add(10*time.Second)) {
		t.Fatal("did not recover same network after offline")
	}
}
