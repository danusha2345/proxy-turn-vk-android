package main

import (
	"bytes"
	"testing"
)

func TestObfsModesRoundTrip(t *testing.T) {
	key, err := deriveWrapKey("test-password")
	if err != nil {
		t.Fatal(err)
	}
	payload := []byte("wireguard-packet")
	for _, tc := range []struct {
		mode string
		pt   byte
		pad  int
	}{{"audio", 111, 24}, {"video", 96, 60}} {
		t.Run(tc.mode, func(t *testing.T) {
			cfg := NewObfsConfig(tc.mode)
			if cfg.PayloadType != tc.pt || cfg.PaddingMax != tc.pad {
				t.Fatalf("config = PT %d, padding %d", cfg.PayloadType, cfg.PaddingMax)
			}
			wire, err := obfsWrapPacket(key, payload, cfg, NewObfsState())
			if err != nil {
				t.Fatal(err)
			}
			if !obfsIsRTPPacket(wire) {
				t.Fatal("wrapped packet not recognized")
			}
			plain := make([]byte, len(payload))
			n, err := obfsUnwrapPacket(key, wire, plain)
			if err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(plain[:n], payload) {
				t.Fatalf("round trip = %q", plain[:n])
			}
		})
	}
}

func TestUnknownObfsModeFallsBackToAudio(t *testing.T) {
	cfg := NewObfsConfig("unknown")
	if cfg.PayloadType != 111 || cfg.PaddingMax != 24 {
		t.Fatalf("fallback = PT %d, padding %d", cfg.PayloadType, cfg.PaddingMax)
	}
}
