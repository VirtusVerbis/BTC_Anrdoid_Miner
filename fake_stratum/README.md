# Fake Stratum Server

Minimal Stratum TCP server for testing the BTC Android Miner app without a real pool. It repeats the same block template on a timer, uses a configurable difficulty, and returns configurable or random Accept/Reject for share submits.

## Requirements

- Python 3.7+

## Run

From the repo root or inside `fake_stratum/`:

```bash
python fake_stratum/fake_stratum.py --host 0.0.0.0 --port 3333 --notify-interval 30 --difficulty 1
```

Send new job only after each share (no timer; miner keeps same job until it submits):

```bash
python fake_stratum/fake_stratum.py --notify-on-share-only --difficulty 1
```

Or with optional accept-nonce (accept only when the miner submits nonce `0`):

```bash
python fake_stratum/fake_stratum.py --notify-interval 10 --difficulty 1 --accept-nonce 0
```

## CLI options

| Option | Default | Description |
|--------|---------|-------------|
| `--host` | `0.0.0.0` | Bind address |
| `--port` | `3333` | Listen port |
| `--notify-interval` | `30` | Seconds between re-sending the same block template (new job_id each time); ignored if `--notify-on-share-only` |
| `--difficulty` | `1.0` | Value sent in `mining.set_difficulty` |
| `--accept-nonce` | (none) | If set, accept only when submitted nonce (hex) equals this value (e.g. `0`) |
| `--notify-on-share-only` | off | Send new `mining.notify` only after each share submit; no periodic timer so the miner is not interrupted |

## App configuration

1. Find your desktop’s local IP (e.g. `192.168.1.100`).
2. In the app: **Config** → set **Stratum URL** to that IP (no `stratum+tcp://` needed if the app adds it; otherwise use `stratum+tcp://192.168.1.100`).
3. Set **Port** to the same value as `--port` (e.g. `3333`).
4. Start the fake server, then in the app tap **Start Mining**.

The app will connect, receive `mining.set_difficulty` and `mining.notify`. By default the server sends a new job every `--notify-interval` seconds. Use `--notify-on-share-only` to send a new job only after each share submit so the miner keeps the same job until it finds and submits a share. Submits are answered with random Accept/Reject unless `--accept-nonce` is set.
