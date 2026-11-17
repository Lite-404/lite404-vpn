package main

import (
	"fmt"
	"net/http"
	"testing"
	"time"

	fhttp "github.com/bogdanfinn/fhttp"
)

func TestVKStartupPacingOnlyWaitsWhenBackoffApplies(t *testing.T) {
	now := time.Now()
	if delay := vkRequestDelay(now, now, time.Time{}, 0); delay != 0 {
		t.Fatalf("successful startup request delayed the next group by %v", delay)
	}
	if delay := vkRequestDelay(now, now, now.Add(7*time.Second), 0); delay != 7*time.Second {
		t.Fatalf("startup did not honor the server backoff: %v", delay)
	}
	if delay := vkRequestDelay(now, now.Add(-time.Second), time.Time{}, 3*time.Second); delay != 2*time.Second {
		t.Fatalf("background refresh lost its pacing: %v", delay)
	}
}

func TestVKRateLimitRecognizesHTTPRetryAfterAndVKFloodControl(t *testing.T) {
	resp := &fhttp.Response{StatusCode: http.StatusTooManyRequests, Header: fhttp.Header{"Retry-After": {"15"}}}
	limit := vkHTTPRateLimit(resp)
	if limit == nil || time.Until(limit.retryAt) < 14*time.Second {
		t.Fatal("HTTP rate limit should carry the retry time")
	}
	api := vkResponseRateLimit(map[string]interface{}{"error": map[string]interface{}{"error_code": float64(29)}})
	if limit := asVKRateLimit(fmt.Errorf("bootstrap: %w", api)); limit == nil || limit.code != 29 {
		t.Fatal("wrapped flood-control error should trigger backoff")
	}
}
