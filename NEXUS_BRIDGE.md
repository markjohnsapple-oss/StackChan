# Nexus Bridge firmware

This branch adds a local Nexus bridge to the M5Stack StackChan CoreS3 firmware.

## Local endpoints

- Status: `http://<STACKCHAN-IP>:8765/nexus/status`
- WebSocket: `ws://<STACKCHAN-IP>:8765/nexus/ws`

## First bridge commands

JSON messages over the WebSocket:

- `{"type":"hello"}`
- `{"type":"sync_personality","text":"..."}`
- `{"type":"sync_memory","text":"..."}`
- `{"type":"voice","name":"Cutey (en-US)"}`
- `{"type":"move","yaw":20,"pitch":25,"speed":250}`
- `{"type":"home"}`
- `{"type":"led","r":0,"g":80,"b":120}`
- `{"type":"speak","text":"Connected to Nexus"}`

Phase 1 verifies the physical live link and stores Nexus identity/memory locally.
The `speak` command displays the text on the physical StackChan and acknowledges it; streamed TTS audio is intentionally not claimed yet.

## Build

GitHub Actions builds the firmware on every push to this branch and creates a merged flash binary:
`stackchan-nexus-bridge-full.bin`.
