#!/usr/bin/env python3
"""Repair / verify a vendored ZIP whose offsets were shifted after it was written.

WHY THIS EXISTS
---------------
`assets/android-sdk-tools-aarch64.zip` is vendored verbatim from a third-party
release (lzhiyong/android-sdk-tools). The published blob for 35.0.2 has 512 KiB
inserted part-way through the member data, and nothing was fixed up afterwards:

  * the EOCD's "offset of central directory" points 524288 bytes too early, so
    random-access readers (Info-ZIP `unzip`, `java.util.zip.ZipFile`, Python
    `zipfile`) cannot even locate the directory -- `unzip` reports the misleading
    "overlapped components (possible zip bomb)";
  * the per-entry local-header offsets are split: members before the insertion
    point are still correct, members after it are all 524288 bytes low;
  * streaming readers (`ZipInputStream`, which is what RootfsManager uses on
    device) get through the first members and then throw mid-inflate, so the
    device silently ends up with NO bundled SDK tools at all.

Every member is still recoverable: the central directory is intact and each
member's deflate stream is undamaged, just relocated. This script rebuilds a
canonical archive from it.

HOW IT WORKS (no magic constants)
---------------------------------
The central directory is treated as the only authority for name / method / crc /
sizes. For each entry we locate its *physical* local header by searching for a
`PK\\x03\\x04` record whose name matches and whose body inflates to the recorded
CRC and uncompressed size. The displacement of the first mismatching entry
becomes the candidate delta reused for the rest, so a single insertion (or
deletion) anywhere in the file is handled without hardcoding 524288.

Nothing is written unless every entry verifies. `--verify` never writes.

Usage:
    repair_vendor_zip.py --verify  ARCHIVE.zip
    repair_vendor_zip.py           ARCHIVE.zip [-o OUT.zip] [--in-place]
"""

from __future__ import annotations

import argparse
import os
import struct
import sys
import zipfile
import zlib

EOCD_SIG = b"PK\x05\x06"
EOCD64_SIG = b"PK\x06\x06"
EOCD64_LOC_SIG = b"PK\x06\x07"
CD_SIG = b"PK\x01\x02"
LFH_SIG = b"PK\x03\x04"

# Largest EOCD comment (0xFFFF) plus the 22-byte record.
EOCD_SCAN_WINDOW = 0xFFFF + 22


class ZipDamage(Exception):
    """Raised when the archive cannot be repaired deterministically."""


def find_eocd(buf: bytes) -> int:
    """Locate the End Of Central Directory record, scanning from the tail.

    The comment length is attacker/author controlled, so `rfind` on the whole
    file is the only reliable way; the 4-byte signature can also appear inside
    member data.
    """
    start = max(0, len(buf) - EOCD_SCAN_WINDOW)
    idx = buf.rfind(EOCD_SIG, start)
    if idx < 0:
        raise ZipDamage("no EOCD signature in the last %d bytes" % EOCD_SCAN_WINDOW)
    if len(buf) - idx < 22:
        raise ZipDamage("truncated EOCD at %d" % idx)
    return idx


def read_eocd(buf: bytes, idx: int) -> tuple[int, int, int]:
    """Return (entry_count, cd_size, cd_offset) honouring the zip64 locator."""
    count, cd_size, cd_off = struct.unpack_from("<HII", buf, idx + 10)
    # zip64: any 0xFFFF / 0xFFFFFFFF field means "look in the EOCD64".
    if idx >= 20 and buf[idx - 20: idx - 16] == EOCD64_LOC_SIG:
        eocd64_off = struct.unpack_from("<Q", buf, idx - 16)[0]
        if buf[eocd64_off: eocd64_off + 4] == EOCD64_SIG:
            z_count = struct.unpack_from("<Q", buf, eocd64_off + 32)[0]
            z_size = struct.unpack_from("<Q", buf, eocd64_off + 40)[0]
            z_off = struct.unpack_from("<Q", buf, eocd64_off + 48)[0]
            if count == 0xFFFF:
                count = z_count
            if cd_size == 0xFFFFFFFF:
                cd_size = z_size
            if cd_off == 0xFFFFFFFF:
                cd_off = z_off
    return count, cd_size, cd_off


def locate_central_directory(buf: bytes, count: int, cd_size: int, cd_off: int) -> int:
    """Find where the central directory *physically* starts.

    `cd_off` is exactly the field that a post-hoc insertion invalidates, so it
    is only a hint. We accept a position when `count` well-formed records of
    exactly `cd_size` bytes end precisely at the EOCD -- that is a far stronger
    predicate than "there is a PK\\x01\\x02 here".
    """
    eocd_idx = find_eocd(buf)
    candidates = []
    if 0 <= cd_off < len(buf):
        candidates.append(cd_off)
    # The directory is contiguous with the EOCD in a well-formed archive, so the
    # most likely true position is derivable from its size alone.
    if 0 <= eocd_idx - cd_size < len(buf):
        candidates.append(eocd_idx - cd_size)

    for base in candidates:
        if _cd_run_is_valid(buf, base, count, cd_size, eocd_idx):
            return base

    # Last resort: scan for any position satisfying the same predicate.
    pos = buf.find(CD_SIG)
    while pos != -1:
        if _cd_run_is_valid(buf, pos, count, cd_size, eocd_idx):
            return pos
        pos = buf.find(CD_SIG, pos + 1)
    raise ZipDamage(
        "cannot locate a central directory of %d entries / %d bytes ending at the EOCD"
        % (count, cd_size)
    )


def _cd_run_is_valid(buf: bytes, base: int, count: int, cd_size: int, eocd_idx: int) -> bool:
    if base + cd_size != eocd_idx:
        return False
    off = base
    try:
        for _ in range(count):
            if buf[off: off + 4] != CD_SIG:
                return False
            nlen, elen, clen = struct.unpack_from("<HHH", buf, off + 28)
            off += 46 + nlen + elen + clen
    except struct.error:
        return False
    return off == base + cd_size


def parse_central_directory(buf: bytes, base: int, count: int) -> list[dict]:
    entries = []
    off = base
    for _ in range(count):
        if buf[off: off + 4] != CD_SIG:
            raise ZipDamage("central directory truncated at %d" % off)
        (
            _sig, ver_made, ver_need, flags, method, _mtime, _mdate,
            crc, csize, usize, nlen, elen, clen, _dstart, _iattr, eattr, lho,
        ) = struct.unpack_from("<IHHHHHHIIIHHHHHII", buf, off)
        name = buf[off + 46: off + 46 + nlen].decode("utf-8", "surrogateescape")
        extra = buf[off + 46 + nlen: off + 46 + nlen + elen]
        comment = buf[off + 46 + nlen + elen: off + 46 + nlen + elen + clen]
        entries.append({
            "name": name,
            "method": method,
            "flags": flags,
            "crc": crc,
            "csize": csize,
            "usize": usize,
            "lho": lho,
            "external_attr": eattr,
            "internal_attr": _iattr,
            "version_made_by": ver_made,
            "extra": extra,
            "comment": comment,
        })
        off += 46 + nlen + elen + clen
    return entries


def _inflate_body(buf: bytes, entry: dict, data_start: int) -> bytes | None:
    """Return the decompressed body, or None if it does not match the directory."""
    end = data_start + entry["csize"]
    if end > len(buf):
        return None
    body = buf[data_start: end]
    if entry["method"] == 0:
        raw = body
    elif entry["method"] == 8:
        try:
            raw = zlib.decompressobj(-15).decompress(body)
        except zlib.error:
            return None
    else:
        return None  # unsupported compression method
    if len(raw) != entry["usize"]:
        return None
    if (zlib.crc32(raw) & 0xFFFFFFFF) != entry["crc"]:
        return None
    return raw


def _local_header_at(buf: bytes, pos: int, name: str) -> int | None:
    """If a matching local header sits at `pos`, return the offset of its data."""
    if buf[pos: pos + 4] != LFH_SIG:
        return None
    if pos + 30 > len(buf):
        return None
    nlen, elen = struct.unpack_from("<HH", buf, pos + 26)
    if buf[pos + 30: pos + 30 + nlen].decode("utf-8", "surrogateescape") != name:
        return None
    return pos + 30 + nlen + elen


def resolve_entries(buf: bytes, entries: list[dict]) -> tuple[list[dict], list[dict]]:
    """Attach verified member data to every entry.

    Returns (resolved, relocations) where each relocation records the physical
    displacement that had to be applied. Raises ZipDamage if any member cannot
    be recovered, so we never emit a silently-short archive.
    """
    resolved = []
    relocations = []
    known_delta: int | None = None

    for entry in entries:
        data_start = None
        delta = 0

        # 1. The offset as recorded. Correct for every member that precedes the
        #    damage, which is the common case.
        data_start = _local_header_at(buf, entry["lho"], entry["name"])
        if data_start is not None and _inflate_body(buf, entry, data_start) is None:
            # Header matches but the body does not: the member was relocated.
            data_start = None

        # 2. A displacement already observed for an earlier member. One bad
        #    writer pass shifts everything after it by the same amount.
        if data_start is None and known_delta is not None:
            cand = _local_header_at(buf, entry["lho"] + known_delta, entry["name"])
            if cand is not None and _inflate_body(buf, entry, cand) is not None:
                data_start, delta = cand, known_delta

        # 3. Search the whole file for this member's local header. Derives the
        #    delta without assuming its value or sign.
        if data_start is None:
            probe = 0
            while True:
                pos = buf.find(LFH_SIG, probe)
                if pos < 0:
                    break
                probe = pos + 1
                cand = _local_header_at(buf, pos, entry["name"])
                if cand is None:
                    continue
                if _inflate_body(buf, entry, cand) is not None:
                    data_start, delta = cand, pos - entry["lho"]
                    if known_delta is None:
                        known_delta = delta
                    break

        if data_start is None:
            raise ZipDamage(
                "member %r is not recoverable: no local header inflates to "
                "crc=%08x size=%d" % (entry["name"], entry["crc"], entry["usize"])
            )

        raw = _inflate_body(buf, entry, data_start)
        assert raw is not None  # guaranteed by the checks above
        if delta:
            relocations.append({
                "name": entry["name"],
                "recorded": entry["lho"],
                "actual": entry["lho"] + delta,
                "delta": delta,
            })
        entry = dict(entry)
        entry["data"] = raw
        resolved.append(entry)

    return resolved, relocations


def rebuild(entries: list[dict], out_path: str) -> None:
    """Write a canonical archive: correct offsets, EOCD, preserved Unix modes."""
    tmp = out_path + ".tmp"
    with zipfile.ZipFile(tmp, "w", allowZip64=False) as zf:
        for e in entries:
            info = zipfile.ZipInfo(filename=e["name"])
            # Preserve the authoring Unix mode (0755 for the tools) -- aapt2 and
            # friends must stay executable for `unzip`-based consumers.
            info.external_attr = e["external_attr"]
            info.internal_attr = e["internal_attr"]
            info.create_system = (e["version_made_by"] >> 8) & 0xFF
            info.compress_type = zipfile.ZIP_DEFLATED if e["method"] == 8 else zipfile.ZIP_STORED
            info.comment = e["comment"]
            if e["name"].endswith("/"):
                # Directory: ZipFile wants no payload and a trailing slash.
                zf.writestr(info, b"")
            else:
                zf.writestr(info, e["data"], compress_type=info.compress_type)
    os.replace(tmp, out_path)


def audit_archive(path: str) -> dict:
    """Full integrity audit used by both --verify and the unit-test guard."""
    with open(path, "rb") as fh:
        buf = fh.read()
    eocd_idx = find_eocd(buf)
    count, cd_size, cd_off = read_eocd(buf, eocd_idx)
    cd_base = locate_central_directory(buf, count, cd_size, cd_off)
    entries = parse_central_directory(buf, cd_base, count)
    resolved, relocations = resolve_entries(buf, entries)
    return {
        "path": path,
        "size": len(buf),
        "entries": count,
        "eocd_offset": eocd_idx,
        "cd_recorded_offset": cd_off,
        "cd_actual_offset": cd_base,
        "cd_offset_delta": cd_base - cd_off,
        "relocated": relocations,
        "members": [
            {"name": e["name"], "method": e["method"], "crc": e["crc"],
             "usize": e["usize"], "csize": e["csize"], "mode": (e["external_attr"] >> 16) & 0xFFFF}
            for e in resolved
        ],
    }


def strict_readers_agree(path: str) -> list[str]:
    """Cross-check with a strict random-access reader.

    Repairing the byte layout is not enough -- the whole point is that standard
    tooling can open the result. Python's `zipfile.ZipFile` reads via the
    central directory and is as strict as `java.util.zip.ZipFile`.
    """
    problems = []
    try:
        with zipfile.ZipFile(path) as zf:
            bad = zf.testzip()
            if bad is not None:
                problems.append("ZipFile.testzip() failed on %r" % bad)
    except Exception as exc:  # noqa: BLE001 - any failure is a verdict
        problems.append("ZipFile cannot open the archive: %s" % exc)
    return problems


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("archive")
    ap.add_argument("-o", "--output", help="where to write the repaired archive")
    ap.add_argument("--in-place", action="store_true",
                    help="overwrite the input archive (implies a .bak alongside)")
    ap.add_argument("--verify", action="store_true",
                    help="only audit; exit 1 if the archive is damaged")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args(argv)

    def say(msg: str) -> None:
        if not args.quiet:
            print(msg)

    try:
        report = audit_archive(args.archive)
    except ZipDamage as exc:
        print("ERROR: %s: %s" % (args.archive, exc), file=sys.stderr)
        return 2

    shifted = report["cd_offset_delta"] or len(report["relocated"])
    say("%s: %d members, %.1f MiB" % (args.archive, report["entries"], report["size"] / 1048576))
    if shifted:
        say("  DAMAGED: central-directory offset off by %+d; %d member(s) relocated"
            % (report["cd_offset_delta"], len(report["relocated"])))
        for rel in report["relocated"]:
            say("    %-34s recorded %9d -> actual %9d (%+d)"
                % (rel["name"], rel["recorded"], rel["actual"], rel["delta"]))
    else:
        say("  OK: offsets self-consistent")

    if args.verify:
        problems = strict_readers_agree(args.archive)
        for p in problems:
            say("  %s" % p)
        # Damaged layout OR a strict reader rejecting it both count as failure.
        return 1 if (shifted or problems) else 0

    if not shifted:
        problems = strict_readers_agree(args.archive)
        if not problems:
            say("  nothing to do")
            return 0
        say("  offsets are consistent but a strict reader rejects it; rebuilding anyway")

    if args.in_place:
        out = args.archive
    elif args.output:
        out = args.output
    else:
        print("ERROR: pass -o/--output or --in-place", file=sys.stderr)
        return 2

    if args.in_place and not os.path.exists(out + ".bak"):
        with open(out, "rb") as src, open(out + ".bak", "wb") as dst:
            dst.write(src.read())
        say("  backup: %s.bak" % out)

    with open(args.archive, "rb") as fh:
        buf = fh.read()
    eocd_idx = find_eocd(buf)
    count, cd_size, cd_off = read_eocd(buf, eocd_idx)
    cd_base = locate_central_directory(buf, count, cd_size, cd_off)
    entries = parse_central_directory(buf, cd_base, count)
    resolved, _ = resolve_entries(buf, entries)
    rebuild(resolved, out)

    problems = strict_readers_agree(out)
    if problems:
        for p in problems:
            print("ERROR: repaired archive still failing: %s" % p, file=sys.stderr)
        return 1
    after = audit_archive(out)
    say("  wrote %s: %d members, %.1f MiB, strict readers OK"
        % (out, after["entries"], after["size"] / 1048576))
    return 0


if __name__ == "__main__":
    sys.exit(main())
