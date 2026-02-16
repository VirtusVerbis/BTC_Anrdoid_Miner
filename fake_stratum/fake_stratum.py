#!/usr/bin/env python3
"""
Fake Stratum TCP server for testing the BTC Android Miner app.
Sends a real past-block template repeatedly at a configurable interval,
uses configurable difficulty, and returns random Accept/Reject for submits.

Usage:
  python fake_stratum.py --host 0.0.0.0 --port 3333 --notify-interval 30 --difficulty 1
  python fake_stratum.py --notify-interval 10 --difficulty 1 --accept-nonce 0

Then in the app: Config -> Stratum URL = stratum+tcp://YOUR_DESKTOP_IP, Port = 3333
"""

import argparse
import json
import logging
import random
import socket
import sys
import threading
import time
from typing import Optional

# Block template from Bitcoin block 0 (genesis) - minimal valid mining.notify params.
# prevhash = 32 zero bytes (no previous block); version/nbits/ntime from genesis.
# coinb1/coinb2 are minimal so coinbase = coinb1 + extranonce1 + extranonce2 + coinb2 hashes to 32 bytes.
BLOCK_TEMPLATE = {
    "prevhash": "0" * 64,
    "version": "01000000",
    "nbits": "ffff001d",
    "ntime": "29ab5f49",
    "coinb1": "0000000000000000000000000000000000000000000000000000000000000000",
    "coinb2": "0000000000000000000000000000000000000000000000000000000000000000",
    "merkle_branch": [],
}


def make_notify(job_id: str, clean_jobs: bool = False) -> dict:
    t = BLOCK_TEMPLATE
    return {
        "method": "mining.notify",
        "params": [
            job_id,
            t["prevhash"],
            t["coinb1"],
            t["coinb2"],
            t["merkle_branch"],
            t["version"],
            t["nbits"],
            t["ntime"],
            clean_jobs,
        ],
    }


def handle_client(
    conn: socket.socket,
    addr,
    difficulty: float,
    notify_interval: int,
    accept_nonce: Optional[int],
    notify_on_share_only: bool,
    force_response: Optional[str],
) -> None:
    log = logging.getLogger(__name__)
    prefix = f"[{addr[0]}:{addr[1]}]"
    extranonce1 = "deadbeef"
    job_counter = [0]
    stop_timer = threading.Event()

    def send(obj: dict) -> None:
        line = json.dumps(obj) + "\n"
        try:
            conn.sendall(line.encode("utf-8"))
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass

    def send_notify(clean_jobs: bool = True) -> None:
        job_counter[0] += 1
        job_id = f"test_{job_counter[0]}"
        send(make_notify(job_id, clean_jobs=clean_jobs))
        log.info("%s sent mining.notify job_id=%s", prefix, job_id)

    def notify_loop() -> None:
        while not stop_timer.wait(timeout=notify_interval):
            send_notify()

    try:
        if not notify_on_share_only:
            timer_thread = threading.Thread(target=notify_loop, daemon=True)
            timer_thread.start()

        buf = b""
        while True:
            data = conn.recv(4096)
            if not data:
                break
            buf += data
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                line = line.decode("utf-8", errors="replace").strip()
                if not line:
                    continue
                try:
                    msg = json.loads(line)
                except json.JSONDecodeError:
                    continue

                method = msg.get("method")
                params = msg.get("params", [])
                req_id = msg.get("id")

                if method == "mining.subscribe":
                    log.info("%s received mining.subscribe", prefix)
                    send({
                        "id": req_id,
                        "result": ["session", extranonce1, 4],
                    })
                elif method == "mining.authorize":
                    user = params[0] if params else "?"
                    log.info("%s received mining.authorize (user=%s)", prefix, user)
                    send({"id": req_id, "result": True})
                    send({"method": "mining.set_difficulty", "params": [difficulty]})
                    log.info("%s sent mining.set_difficulty and initial mining.notify", prefix)
                    send_notify(clean_jobs=True)
                elif method == "mining.extranonce.subscribe":
                    log.info("%s received mining.extranonce.subscribe", prefix)
                    send({"id": req_id, "result": True})
                elif method == "mining.submit":
                    job_id = params[1] if len(params) > 1 else "?"
                    en2 = params[2] if len(params) > 2 else "?"
                    ntime = params[3] if len(params) > 3 else "?"
                    nonce_hex = params[4] if len(params) > 4 else "?"
                    if force_response == "accepted":
                        accepted = True
                    elif force_response == "rejected":
                        accepted = False
                    elif accept_nonce is not None:
                        want = f"{accept_nonce:08x}"
                        accepted = nonce_hex.lower() == want.lower()
                    else:
                        accepted = random.choice([True, False])
                    result_str = "Accepted" if accepted else "Rejected"
                    log.info(
                        "%s submit job_id=%s extranonce2=%s ntime=%s nonce=%s -> %s",
                        prefix, job_id, en2, ntime, nonce_hex, result_str,
                    )
                    if accepted:
                        send({"id": req_id, "result": True})
                    else:
                        send({
                            "id": req_id,
                            "result": None,
                            "error": [25, "Rejected"],
                        })
                    if notify_on_share_only:
                        send_notify(clean_jobs=True)
                else:
                    log.info("%s received unknown method: %s", prefix, method)

    except (BrokenPipeError, ConnectionResetError, OSError, json.JSONDecodeError):
        pass
    finally:
        stop_timer.set()
        log.info("%s client disconnected", prefix)
    try:
        conn.close()
    except OSError:
        pass


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Fake Stratum server for testing BTC Android Miner"
    )
    parser.add_argument(
        "--host",
        default="0.0.0.0",
        help="Bind address (default: 0.0.0.0)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=3333,
        help="Listen port (default: 3333)",
    )
    parser.add_argument(
        "--notify-interval",
        type=int,
        default=30,
        metavar="SECONDS",
        help="Seconds between re-sending the same block template (default: 30)",
    )
    parser.add_argument(
        "--difficulty",
        type=float,
        default=1.0,
        help="Pool difficulty in (0, 1] for mining.set_difficulty (default: 1)",
    )
    parser.add_argument(
        "--accept-nonce",
        type=int,
        default=None,
        metavar="N",
        help="If set, accept only when submitted nonce (hex) equals this value (e.g. 0)",
    )
    parser.add_argument(
        "--notify-on-share-only",
        action="store_true",
        help="Send new mining.notify only after each share submit (no timer); miner keeps same job until it submits",
    )
    parser.add_argument(
        "--response",
        choices=["accepted", "rejected"],
        default=None,
        help="Force all share submits to be Accepted or Rejected; if not set, use --accept-nonce or random.",
    )
    args = parser.parse_args()

    if args.difficulty <= 0 or args.difficulty > 1:
        logging.basicConfig(
            level=logging.INFO,
            format="%(asctime)s %(message)s",
            datefmt="%H:%M:%S",
            stream=sys.stderr,
            force=True,
        )
        logging.getLogger(__name__).error("difficulty must be in (0, 1]")
        return 1

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(message)s",
        datefmt="%H:%M:%S",
        stream=sys.stderr,
        force=True,
    )
    log = logging.getLogger(__name__)

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        server.bind((args.host, args.port))
    except OSError as e:
        log.error("Bind failed: %s", e)
        return 1
    server.listen(5)
    mode = "notify_on_share_only" if args.notify_on_share_only else f"notify_interval={args.notify_interval}s"
    log.info(
        "Fake Stratum listening on %s:%s (%s, difficulty=%s)",
        args.host, args.port, mode, args.difficulty,
    )

    while True:
        conn, addr = server.accept()
        log.info("Client connected: %s:%s", addr[0], addr[1])
        t = threading.Thread(
            target=handle_client,
            args=(conn, addr, args.difficulty, args.notify_interval, args.accept_nonce, args.notify_on_share_only, args.response),
            daemon=True,
        )
        t.start()


if __name__ == "__main__":
    sys.exit(main() or 0)
