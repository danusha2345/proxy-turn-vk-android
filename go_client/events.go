package main

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
)

var eventOutputEnabled = os.Getenv("WDTT_EVENTS") == "1"

func emitEvent(eventType string, payload map[string]any) {
	if !eventOutputEnabled {
		return
	}
	data, err := json.Marshal(payload)
	if err != nil {
		log.Printf("[EVENT] marshal %s: %v", eventType, err)
		return
	}
	fmt.Printf("__WDTT_EVENT__|%s|%s\n", eventType, data)
}

func emitStats(s *Stats) {
	emitEvent("STATS", map[string]any{
		"active": s.ActiveConnections.Load(), "bytes_up": s.TotalBytesUp.Load(), "bytes_down": s.TotalBytesDown.Load(),
	})
}

func emitReady() { emitEvent("READY", nil) }

func emitConfig(config string) { emitEvent("CONFIG", map[string]any{"config": config}) }
