#!/usr/bin/env python3
"""Boot only generated test files in QEMU; never accepts a physical target device."""
import argparse
import json
import pathlib
import shutil
import socket
import subprocess
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("media", type=pathlib.Path)
    parser.add_argument("target", type=pathlib.Path)
    parser.add_argument("output", type=pathlib.Path)
    parser.add_argument("--timeout", type=int, default=7200)
    parser.add_argument("--firmware", type=pathlib.Path, default=pathlib.Path("/usr/share/OVMF"))
    args = parser.parse_args()
    if not args.media.is_file() or args.media.resolve() == args.target.resolve():
        parser.error("Use a generated media image and a separate new target file")
    if not pathlib.Path("/dev/kvm").exists():
        parser.error("The full Windows installation test requires KVM")
    args.output.mkdir(parents=True, exist_ok=True)
    with args.target.open("xb") as target:
        target.truncate(64 << 30)
    code = args.firmware / "OVMF_CODE_4M.fd"
    variables = args.output / "OVMF_VARS.fd"
    shutil.copyfile(args.firmware / "OVMF_VARS_4M.fd", variables)
    qmp_path = args.output.resolve() / "qmp.sock"
    log_path = args.output / "qemu.log"
    command = ["qemu-system-x86_64", "-machine", "q35,accel=kvm", "-cpu", "host", "-m", "6144", "-smp", "4",
               "-drive", f"if=pflash,format=raw,readonly=on,file={code}",
               "-drive", f"if=pflash,format=raw,file={variables.resolve()}",
               "-drive", f"if=none,id=target,format=raw,cache=writeback,discard=unmap,detect-zeroes=unmap,file={args.target.resolve()}",
               "-device", "ide-hd,drive=target,bus=ide.0,rotation_rate=1,bootindex=2",
               "-device", "qemu-xhci,id=xhci",
               "-drive", f"if=none,id=install,format=raw,cache=writeback,file={args.media.resolve()}",
               "-device", "usb-storage,id=installusb,drive=install,bus=xhci.0,removable=on,bootindex=1",
               "-display", "none", "-vga", "std", "-net", "none", "-serial", "file:" + str(args.output.resolve() / "serial.log"),
               "-monitor", "none", "-qmp", f"unix:{qmp_path},server=on,wait=off"]
    started = time.monotonic()
    with log_path.open("wb") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
        stream = None
        connection = socket.socket(socket.AF_UNIX)
        try:
            while not qmp_path.exists():
                if process.poll() is not None or time.monotonic() - started > 30:
                    raise RuntimeError("QEMU did not start: " + log_path.read_text())
                time.sleep(.2)
            connection.connect(str(qmp_path))
            connection.settimeout(5)
            stream = connection.makefile("rwb", buffering=0)
            greeting = json.loads(stream.readline())
            if "QMP" not in greeting:
                raise RuntimeError("Invalid QMP greeting")
            events = []
            sequence = 0

            def execute(name, arguments=None):
                nonlocal sequence
                sequence += 1
                request = {"execute": name, "id": sequence}
                if arguments is not None:
                    request["arguments"] = arguments
                stream.write(json.dumps(request).encode() + b"\n")
                while True:
                    line = stream.readline()
                    if not line:
                        raise EOFError("QEMU closed QMP")
                    response = json.loads(line)
                    if "event" in response:
                        events.append(response)
                    elif response.get("id") == sequence:
                        if "error" in response:
                            raise RuntimeError(response["error"])
                        return response.get("return")

            execute("qmp_capabilities")
            detached = False
            next_capture = 0
            capture = 0
            while process.poll() is None:
                elapsed = time.monotonic() - started
                if elapsed > args.timeout:
                    raise TimeoutError("Windows did not reach the first-logon probe before the deadline")
                if elapsed >= next_capture:
                    capture += 1
                    execute("screendump", {"filename": str(args.output.resolve() / f"screen-{capture:02}.ppm")})
                    snapshot = {
                        "elapsed_seconds": elapsed,
                        "block_stats": execute("query-blockstats"),
                        "block_devices": execute("query-block"),
                        "cpus": execute("query-cpus-fast"),
                        "host_process_stat": pathlib.Path(f"/proc/{process.pid}/stat").read_text(),
                        "host_process_io": pathlib.Path(f"/proc/{process.pid}/io").read_text(),
                        "host_disk_free_bytes": shutil.disk_usage(args.target.parent).free,
                        "target_allocated_bytes": args.target.stat().st_blocks * 512,
                    }
                    (args.output / f"activity-{capture:02}.json").write_text(json.dumps(snapshot, indent=2))
                    activity = [{"device": item.get("device"), "read_bytes": item["stats"].get("rd_bytes"),
                                 "written_bytes": item["stats"].get("wr_bytes")} for item in snapshot["block_stats"]]
                    next_capture = elapsed + 120
                    print(f"Windows installation running: {elapsed:.0f}s; disk activity: {json.dumps(activity)}", flush=True)
                try:
                    execute("query-status")
                except (EOFError, ConnectionError):
                    if process.wait(timeout=5) == 0:
                        break
                    raise
                for event in events:
                    print(json.dumps(event), flush=True)
                    if event.get("event") == "RESET" and event.get("data", {}).get("guest") and not detached:
                        # Setup copied its installation files before its first reboot. Remove only the
                        # generated USB device, so the new Windows Boot Manager starts on the target.
                        execute("device_del", {"id": "installusb"})
                        detached = True
                        print("Removed test USB after the first guest reboot", flush=True)
                events.clear()
                time.sleep(2)
            if process.returncode != 0:
                raise RuntimeError(f"QEMU exited with {process.returncode}")
            print(f"VM shut down after {time.monotonic() - started:.0f}s; checking installed target", flush=True)
        finally:
            if stream:
                stream.close()
            connection.close()
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()

    # Read the third GPT partition, the destination chosen by the unmodified Rufus
    # disk configuration. The VM is stopped; ntfscat never reads a live guest filesystem.
    with args.target.open("rb") as target:
        target.seek(512)
        header = target.read(512)
        if header[:8] != b"EFI PART":
            raise AssertionError("Windows did not create a GPT target")
        table = int.from_bytes(header[72:80], "little")
        entry_size = int.from_bytes(header[84:88], "little")
        if entry_size < 128 or entry_size > 4096:
            raise AssertionError("Invalid Windows GPT entry size")
        target.seek(table * 512 + 2 * entry_size)
        partition = target.read(entry_size)
        start = int.from_bytes(partition[32:40], "little")
        end = int.from_bytes(partition[40:48], "little")
        if not start or end < start or (end + 1) * 512 > args.target.stat().st_size:
            raise AssertionError("Windows target partition is missing")
    device = subprocess.check_output(["sudo", "losetup", "--find", "--show", "--read-only", "--offset", str(start * 512),
                                      "--sizelimit", str((end - start + 1) * 512), str(args.target.resolve())], text=True).strip()
    try:
        result = subprocess.check_output(["sudo", "ntfscat", device, "/rufus-port-test.json"])
        (args.output / "installed-windows.json").write_bytes(result)
        for name in ("setupact.log", "setuperr.log"):
            with (args.output / name).open("wb") as output:
                subprocess.run(["sudo", "ntfscat", device, "/Windows/Panther/" + name], stdout=output, check=False)
        information = json.loads(result.decode("utf-8-sig"))
        assert information["User"].lower() == "rufustest", information
        assert information["Caption"].startswith("Microsoft Windows 11"), information
        assert int(information["Build"]) >= 22000, information
        assert information["ImageState"] == "IMAGE_STATE_COMPLETE", information
        assert information["Culture"].lower() == "en-us", information
        assert information["Timezone"] == "UTC", information
        print("Windows installation and first-logon probe passed:", json.dumps(information), flush=True)
    finally:
        subprocess.run(["sudo", "losetup", "--detach", device], check=True)


if __name__ == "__main__":
    main()
