"""No Gradle: validate bundled content and compile/run the isolated JVM rules tests.

Uses existing Kotlin compiler/JUnit jars only. Optional --app-jar compiles the
story Android sources against an already built host jar (not an APK build).
All outputs stay under this test directory's .verification subdirectory.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

here = Path(__file__).resolve().parent
project = next(p for p in here.parents if (p / "settings.gradle.kts").is_file())
parser = argparse.ArgumentParser()
parser.add_argument("--compiler-lib", type=Path, required=True)
parser.add_argument("--app-jar", type=Path)
parser.add_argument("--android-jar", type=Path)
parser.add_argument("--monitor-aar", type=Path)
args = parser.parse_args()
out = here / ".verification"
out.mkdir(exist_ok=True)
source = project / "app/src/main/java/cn/campuslife/assistant/story"
asset = project / "app/src/main/assets/stories.json"
report = {"started_utc": datetime.now(timezone.utc).isoformat(), "commands": [], "source_sha256": {}}


def command(argv):
    completed = subprocess.run([str(a) for a in argv], capture_output=True, encoding="utf-8", errors="replace")
    record = {"argv": [str(a) for a in argv], "exit": completed.returncode,
              "stdout": completed.stdout, "stderr": completed.stderr}
    report["commands"].append(record)
    (out / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(completed.stdout, end="")
    print(completed.stderr, end="")
    if completed.returncode:
        raise SystemExit(completed.returncode)


stories = json.loads(asset.read_text(encoding="utf-8"))["stories"]
assert len(stories) == 12
assert len({s["id"] for s in stories}) == 12
for story in stories:
    assert re.fullmatch(r"story-[a-z0-9][a-z0-9-]{0,69}", story["id"])
    assert 400 <= len(story["body"].strip()) <= 900, story["id"]
    assert story["source"] == "本项目创作" and story["authorLabel"] == "原创短篇"
    assert story["title"].strip() and story["summary"].strip()
assert {s["category"] for s in stories} == {"LIFE", "GROWTH", "WARMTH", "TWIST"}
report["content"] = [{"id": s["id"], "characters": len(s["body"].strip())} for s in stories]
for path in sorted(list(source.glob("*.kt")) + list(here.glob("*.kt")) + [asset]):
    report["source_sha256"][str(path.relative_to(project))] = hashlib.sha256(path.read_bytes()).hexdigest()
lib = args.compiler_lib
stdlib = next(lib.glob("kotlin-stdlib-*.jar"))
junit = next(lib.glob("junit-4*.jar"))
runtime_cp = os.pathsep.join(str(p) for p in [out / "rules-tests.jar", stdlib, junit, next(lib.glob("hamcrest-core-*.jar"))])
compiler = ["java", "-Dfile.encoding=UTF-8", "-cp", str(lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect", "-jvm-target", "17"]
command(compiler + ["-classpath", os.pathsep.join([str(stdlib), str(junit)]), "-d", out / "rules-tests.jar",
                    source / "StoryModels.kt", source / "StoryRules.kt", source / "StorySubmissionConfig.kt", here / "StoryRulesTest.kt"])
command(["java", "-Dfile.encoding=UTF-8", "-cp", runtime_cp, "org.junit.runner.JUnitCore", "cn.campuslife.assistant.story.StoryRulesTest"])
if args.app_jar:
    assert args.android_jar and args.android_jar.is_file()
    cp = os.pathsep.join(str(p) for p in [stdlib, args.app_jar, args.android_jar])
    command(compiler + ["-classpath", cp, "-d", out / "story-android.jar"] + sorted(source.glob("*.kt")))
    if args.monitor_aar:
        monitor = out / "monitor.jar"
        with zipfile.ZipFile(args.monitor_aar) as archive:
            monitor.write_bytes(archive.read("classes.jar"))
        device_tests = sorted((project / "app/src/androidTest/java/cn/campuslife/assistant/story").glob("*.kt"))
        for path in device_tests:
            report["source_sha256"][str(path.relative_to(project))] = hashlib.sha256(path.read_bytes()).hexdigest()
        test_cp = os.pathsep.join([cp, str(junit), str(monitor), str(out / "story-android.jar")])
        command(compiler + ["-classpath", test_cp, "-d", out / "story-device-tests.jar"] + device_tests)
report["finished_utc"] = datetime.now(timezone.utc).isoformat()
(out / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print("Content: 12 passed; raw command evidence: " + str(out / "results.json"))
