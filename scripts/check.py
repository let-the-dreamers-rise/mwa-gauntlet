#!/usr/bin/env python3
"""Compare JUnit results with EXPECTED.json and write a Markdown summary.

pinned:        the gauntlet tests that fail must be exactly the recorded findings.
patched:       every test in every module that ran must pass.
upstream-main: report only (shows which findings still reproduce on upstream main).
"""
import glob, json, os, sys
import xml.etree.ElementTree as ET

mode, android_dir, expected_path = sys.argv[1], sys.argv[2], sys.argv[3]
expected = json.load(open(expected_path))
suite = expected["suite"]

results = {}      # (classname, test) -> "pass" | "fail"
for xml in glob.glob(os.path.join(android_dir, "*", "build", "test-results", "testDebugUnitTest", "*.xml")):
    for case in ET.parse(xml).getroot().iter("testcase"):
        failed = case.find("failure") is not None or case.find("error") is not None
        results[(case.get("classname"), case.get("name"))] = "fail" if failed else "pass"

gauntlet = {name: r for (cls, name), r in results.items() if cls == suite}
others_failed = sorted(f"{c}.{n}" for (c, n), r in results.items() if c != suite and r == "fail")

lines = [f"## mwa-gauntlet: `{mode}`", "",
         f"{len(results)} tests ran in total, {len(gauntlet)} of them gauntlet tests.", "",
         "| Gauntlet test | Result |", "|---|---|"]
for name in sorted(gauntlet):
    lines.append(f"| `{name}` | {'reproduces (fails)' if gauntlet[name] == 'fail' else 'passes'} |")
if others_failed:
    lines += ["", "Other failing tests:", *[f"- `{t}`" for t in others_failed]]

ok = True
problems = []
if not gauntlet:
    ok = False
    problems.append("no gauntlet results found (build failed?)")
elif mode in ("pinned", "patched"):
    want = set(expected[mode]["expected_failures"])
    got = {n for n, r in gauntlet.items() if r == "fail"}
    if got != want:
        ok = False
        if got - want:
            problems.append("unexpected failures: " + ", ".join(sorted(got - want)))
        if want - got:
            problems.append("expected to fail but passed: " + ", ".join(sorted(want - got)))
    if mode == "patched" and others_failed:
        ok = False
        problems.append(f"{len(others_failed)} existing upstream tests fail with the patch")

lines += ["", "**Result:** " + ("as expected" if ok else "MISMATCH: " + "; ".join(problems))]
if mode == "upstream-main":
    still = sorted(n for n, r in gauntlet.items() if r == "fail")
    lines += ["", f"Findings still reproducing on upstream main: {', '.join(still) if still else 'none'}"]
    ok = bool(gauntlet)

out = "\n".join(lines) + "\n"
print(out)
summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a") as f:
        f.write(out)
sys.exit(0 if ok else 1)
