package main

import (
	"net"
	"strings"
	"testing"
)

func TestRequestConfigCompatibility(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	go func() {
		buf := make([]byte, 256)
		n, _ := server.Read(buf)
		if got := string(buf[:n]); got != "GETCONF:9000|device|password" {
			t.Errorf("request = %q", got)
		}
		_, _ = server.Write([]byte("[Interface]\nAddress = 10.66.66.2/32"))
	}()
	conf, err := RequestConfig(client, "9000", "device", "password")
	if err != nil || !strings.Contains(conf, "Address") {
		t.Fatalf("conf=%q err=%v", conf, err)
	}
}

func TestRequestConfigDenied(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	go func() {
		buf := make([]byte, 256)
		_, _ = server.Read(buf)
		_, _ = server.Write([]byte("DENIED:device_mismatch"))
	}()
	_, err := RequestConfig(client, "9000", "device", "password")
	if err == nil || !strings.Contains(err.Error(), "другому устройству") {
		t.Fatalf("err = %v", err)
	}
}
