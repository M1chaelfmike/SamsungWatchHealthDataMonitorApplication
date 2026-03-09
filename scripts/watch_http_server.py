import argparse
import json
from threading import Lock
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from time import monotonic


class WatchRequestHandler(BaseHTTPRequestHandler):
    server_version = "WatchHTTP/1.0"

    def _message_category(self, payload: dict) -> str:
        if payload.get("event") == "wear_state":
            return "wear"

        sensor_type = payload.get("sensorType")
        if sensor_type == "temperature":
            return "temperature"
        if sensor_type == "heart_rate":
            return "heart_rate"
        if sensor_type == "eda":
            return "eda"

        return "other"

    def _write_json(self, status_code: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        self._write_json(200, {"status": "ok", "message": "watch receiver is running"})

    def do_POST(self) -> None:
        content_length = int(self.headers.get("Content-Length", "0"))
        raw_body = self.rfile.read(content_length)

        try:
            payload = json.loads(raw_body.decode("utf-8")) if raw_body else {}
        except json.JSONDecodeError as exc:
            self._write_json(400, {"status": "error", "message": f"invalid json: {exc}"})
            return

        timestamp = datetime.now().isoformat(timespec="seconds")
        record = {
            "receivedAt": timestamp,
            "client": self.client_address[0],
            "path": self.path,
            "payload": payload,
        }

        output_file: Path = self.server.output_file
        output_file.parent.mkdir(parents=True, exist_ok=True)
        with output_file.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")

        print(self._format_console_message(record), flush=True)
        self._write_json(200, {"status": "ok"})

    def log_message(self, format: str, *args) -> None:
        return

    def _format_console_message(self, record: dict) -> str:
        payload = record.get("payload", {})
        category = self._message_category(payload)
        delta_seconds = self.server.mark_received_and_get_delta(category)
        delta_text = "first" if delta_seconds is None else f"+{delta_seconds:.1f}s"
        timestamp = record.get("receivedAt", "-")
        client = record.get("client", "-")

        if payload.get("event") == "wear_state":
            state = payload.get("state", "UNKNOWN")
            return f"[{timestamp}] [{delta_text}] wear {state} from {client}"

        sensor_type = payload.get("sensorType")
        if sensor_type == "temperature":
            temperature = payload.get("temperature") or {}
            return (
                f"[{timestamp}] [{delta_text}] TEMP from {client} | "
                f"ws={temperature.get('wristSkinTemperature', '-')} "
                f"at={temperature.get('ambientTemperature', '-')} "
                f"status={temperature.get('status', '-')}"
            )
        if sensor_type == "heart_rate":
            heart_rate = payload.get("heartRate") or {}
            return (
                f"[{timestamp}] [{delta_text}] HR from {client} | "
                f"bpm={heart_rate.get('bpm', '-')} status={heart_rate.get('status', '-')}"
            )
        if sensor_type == "eda":
            eda = payload.get("eda") or {}
            return (
                f"[{timestamp}] [{delta_text}] EDA from {client} | "
                f"label={eda.get('label', '-')} conductance={eda.get('skinConductance', '-')} "
                f"valid={eda.get('validSampleCount', '-')}"
            )

        eda = payload.get("eda") or {}
        temperature = payload.get("temperature") or {}
        heart_rate = payload.get("heartRate") or {}

        return (
            f"[{timestamp}] [{delta_text}] snapshot from {client} | "
            f"EDA {eda.get('label', '-')}/{eda.get('skinConductance', '-')} | "
            f"TEMP ws={temperature.get('wristSkinTemperature', '-')} at={temperature.get('ambientTemperature', '-')} | "
            f"HR bpm={heart_rate.get('bpm', '-')} status={heart_rate.get('status', '-')}"
        )


class WatchHTTPServer(ThreadingHTTPServer):
    def __init__(self, server_address, request_handler_class):
        super().__init__(server_address, request_handler_class)
        self.output_file = Path()
        self._receive_lock = Lock()
        self._last_received_monotonic_by_category = {}

    def mark_received_and_get_delta(self, category: str):
        now = monotonic()
        with self._receive_lock:
            previous = self._last_received_monotonic_by_category.get(category)
            self._last_received_monotonic_by_category[category] = now
        if previous is None:
            return None
        return now - previous


def main() -> None:
    parser = argparse.ArgumentParser(description="Receive sensor payloads from the watch over HTTP.")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--output", default="received/watch_payloads.jsonl")
    args = parser.parse_args()

    server = WatchHTTPServer((args.host, args.port), WatchRequestHandler)
    server.output_file = Path(args.output)

    print(f"Listening on http://{args.host}:{args.port}/", flush=True)
    print(f"Writing payloads to {server.output_file.resolve()}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()