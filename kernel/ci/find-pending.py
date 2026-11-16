#!/usr/bin/env python3
"""Print the newest devon nightly date (YYYYMMDD) of the given Lineage major that has no k-<date> release yet.

usage: find-pending.py <api.json> <major> [existing tags...]
Prints nothing if the newest build is already released.
"""
import json
import sys


def pending(api, major, tags):
    builds = [b for b in api if b["version"].split(".")[0] == str(major)]
    if not builds:
        return None
    newest = max(builds, key=lambda b: b["date"])
    date = newest["date"].replace("-", "")
    return None if f"k-{date}" in tags else date


def main():
    api = json.load(open(sys.argv[1]))
    d = pending(api, sys.argv[2], set(sys.argv[3:]))
    if d:
        print(d)


if __name__ == "__main__":
    main()
