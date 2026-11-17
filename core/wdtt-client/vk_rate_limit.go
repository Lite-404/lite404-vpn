package main

import (
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"time"

	fhttp "github.com/bogdanfinn/fhttp"
)

type vkRateLimitError struct {
	code    int
	retryAt time.Time
}

func (e *vkRateLimitError) Error() string {
	return fmt.Sprintf("VK rate limit (code %d)", e.code)
}

func asVKRateLimit(err error) *vkRateLimitError {
	var limit *vkRateLimitError
	if errors.As(err, &limit) {
		return limit
	}
	return nil
}

func vkHTTPRateLimit(resp *fhttp.Response) *vkRateLimitError {
	if resp.StatusCode != http.StatusTooManyRequests {
		return nil
	}
	header := resp.Header.Get("Retry-After")
	retryAt, _ := http.ParseTime(header)
	if seconds, err := strconv.ParseUint(header, 10, 32); err == nil {
		retryAt = time.Now().Add(time.Duration(seconds) * time.Second)
	}
	return &vkRateLimitError{code: resp.StatusCode, retryAt: retryAt}
}

func vkResponseRateLimit(resp map[string]interface{}) *vkRateLimitError {
	if code := vkErrorCode(resp["error_code"]); code == http.StatusTooManyRequests {
		return &vkRateLimitError{code: code}
	}
	if apiError, ok := resp["error"].(map[string]interface{}); ok {
		switch code := vkErrorCode(apiError["error_code"]); code {
		case 6, 9, 29, http.StatusTooManyRequests:
			return &vkRateLimitError{code: code}
		}
	}
	return nil
}
