#!/usr/bin/env python3
"""CameraVR desktop preview receiver. Receives length-prefixed JPEG frames over TCP."""
import argparse
import socket
import struct
import cv2
import numpy as np

def read_exact(sock, count):
    data = bytearray()
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            return None
        data.extend(chunk)
    return bytes(data)

def main():
    parser = argparse.ArgumentParser(description="CameraVR PC receiver")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=4242)
    args = parser.parse_args()
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((args.host, args.port))
    server.listen(1)
    print(f"CameraVR aguardando conexão em {args.host}:{args.port}")
    while True:
        conn, address = server.accept()
        print(f"Android conectado: {address}")
        try:
            with conn:
                while True:
                    header = read_exact(conn, 4)
                    if header is None:
                        break
                    size = struct.unpack(">I", header)[0]
                    if size <= 0 or size > 12_000_000:
                        raise ValueError("Tamanho de quadro inválido")
                    payload = read_exact(conn, size)
                    if payload is None:
                        break
                    frame = cv2.imdecode(np.frombuffer(payload, np.uint8), cv2.IMREAD_COLOR)
                    if frame is None:
                        continue
                    # Side-by-side preview for the VR Box format; this is a 2D duplicate.
                    eye = cv2.resize(frame, (640, 720), interpolation=cv2.INTER_AREA)
                    stereo = np.concatenate((eye, eye), axis=1)
                    cv2.imshow("CameraVR PC - VR Box SBS (2D)", stereo)
                    if cv2.waitKey(1) & 0xFF == 27:
                        return
        except (ConnectionError, OSError, ValueError) as exc:
            print(f"Conexão encerrada: {exc}")
        finally:
            cv2.destroyAllWindows()

if __name__ == "__main__":
    main()
