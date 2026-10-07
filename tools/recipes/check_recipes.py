"""Checks the mod's shader-pack recipes against the packs they're for
(docs/shader-packs.md): each pack in packs.json is downloaded, patched by the
built jar's ShaderPackPatcher, and every program the patch changed compiled
with glslang - the original and the patched, the way Iris hands them to the
driver - so that only what the patch broke shows.

    python check_recipes.py --jar <mod jar> --include <dir> [--include <dir>] [--glslang PATH]

--include: the resource packs' minecraft/shaders/include folders the recipes
take the eye and the lava from (RP-Mordor's, then ResourcePackScripts'
shaderBase). Fails if a recipe doesn't take its pack, or the patch breaks a
program that compiled before.
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent

# what Iris defines for a shader pack, enough for every program to compile
DEFINES = ["IS_IRIS", "MC_HAND_DEPTH 0.125", "MC_RENDER_STAGE_PARTICLES 7", "MC_RENDER_QUALITY 1.0",
           "MC_SHADOW_QUALITY 1.0", "MC_TEXTURE_FORMAT_LAB_PBR", "MC_NORMAL_MAP", "MC_SPECULAR_MAP",
           "MC_VERSION 260200", "MC_GL_VERSION 330", "DISTANT_HORIZONS",
           "DH_BLOCK_UNKNOWN 0", "DH_BLOCK_LEAVES 1", "DH_BLOCK_STONE 2", "DH_BLOCK_WOOD 3", "DH_BLOCK_METAL 4",
           "DH_BLOCK_DIRT 5", "DH_BLOCK_LAVA 6", "DH_BLOCK_DEEPSLATE 7", "DH_BLOCK_SNOW 8", "DH_BLOCK_SAND 9",
           "DH_BLOCK_TERRACOTTA 10", "DH_BLOCK_NETHER_STONE 11", "DH_BLOCK_WATER 12", "DH_BLOCK_GRASS 13",
           "DH_BLOCK_AIR 14", "DH_BLOCK_ILLUMINATED 15"]
STAGES = {".fsh": "frag", ".vsh": "vert", ".gsh": "geom"}
INCLUDE = re.compile(r'^[ \t]*#include\s+"([^"]+)"', re.M)


def get(url):
    request = urllib.request.Request(url, headers={"User-Agent": "MCME/MCME-Modpack-Marker recipe check"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def download(pack, cache: Path) -> Path:
    """The pack's zip, from the cache or downloaded into it."""
    cache.mkdir(parents=True, exist_ok=True)
    if "github" in pack:
        target = cache / f"{pack['github'].replace('/', '_')}-{pack['commit']}.zip"
        url = f"https://codeload.github.com/{pack['github']}/zip/{pack['commit']}"
    else:
        target = cache / pack["file"]
        versions = json.loads(get(f"https://api.modrinth.com/v2/project/{pack['modrinth']}/version"))
        files = [f for v in versions if v["version_number"] == pack["version"] for f in v["files"] if f["filename"] == pack["file"]]
        if not files:
            raise RuntimeError(f"Modrinth has no {pack['file']} in {pack['modrinth']} {pack['version']}")
        url = files[0]["url"]
        if target.is_file() and target.stat().st_size == files[0]["size"]:
            return target
    if not target.is_file():
        target.write_bytes(get(url))
    return target


def unpack(archive: Path, into: Path) -> Path:
    """The folder holding the pack's shaders/."""
    with zipfile.ZipFile(archive) as z:
        z.extractall(into)
    roots = [p.parent for p in into.rglob("shaders") if p.is_dir() and (p / "shaders.properties").is_file()]
    if not roots:
        roots = [p.parent for p in into.rglob("shaders") if p.is_dir()]
    return min(roots, key=lambda p: len(p.parts))


def expand(shaders: Path, text: str, here: Path, seen: set) -> str:
    def include(match):
        name = match.group(1)
        path = (shaders / name.lstrip("/")) if name.startswith("/") else (here / name)
        if not path.is_file() or path in seen:
            return f"// missing {name}"
        return expand(shaders, path.read_text(encoding="utf-8", errors="replace"), path.parent, seen | {path})
    return INCLUDE.sub(include, text)


def as_iris(root: Path, program: Path) -> str:
    """The program as Iris gives it to the driver, near enough for glslang."""
    shaders = root / "shaders"
    original = program.read_text(encoding="utf-8", errors="replace")
    src = expand(shaders, original, program.parent, {program})
    src = re.sub(r"^\s*#version\s+\d+[^\n]*", "", src, count=1, flags=re.M)
    extensions = "".join(re.findall(r"^\s*#extension[^\n]*\n", src, flags=re.M))
    src = re.sub(r"^\s*#extension[^\n]*\n", "", src, flags=re.M)
    src = re.sub(r"\\\r?\n", "", src)
    src = re.sub(r"\btexelFetch2D\b", "texelFetch", src)
    src = re.sub(r"\btexture2DLod\b", "textureLod", src)
    src = re.sub(r"\btexture2DGradARB\b|\btexture2DGrad\b", "textureGrad", src)
    src = re.sub(r"uniform\s+sampler2D\s+texture\s*;", "uniform sampler2D gtexture;", src)
    src = re.sub(r"\btexture\b(?!\s*\()", "gtexture", src)
    iris = "int dhMaterialId;\n" if program.name.startswith("dh_") and program.suffix == ".vsh" else ""
    # Iris turns gl_FragData[n] into layout(location = n) outs
    if program.suffix == ".fsh" and re.search(r"layout\s*\(\s*location\s*=\s*\d+\s*\)\s*out", src):
        used = sorted(set(re.findall(r"\bgl_FragData\s*\[\s*(\d+)\s*\]", src)))
        src = re.sub(r"\bgl_FragData\s*\[\s*(\d+)\s*\]", r"iris_FragData\1", src)
        iris += "".join(f"layout(location = {n}) out vec4 iris_FragData{n};\n" for n in used)
    version = re.search(r"#version\s+(\d+)", original)
    version = version.group(1) if version and int(version.group(1)) > 330 else "330"
    return f"#version {version} compatibility\n" + extensions + "".join(f"#define {d}\n" for d in DEFINES) + iris + src


def programs(root: Path) -> dict:
    """Every program the game runs, relative path -> file: shaders/ and its world folders."""
    shaders = root / "shaders"
    found = {}
    for folder in [shaders] + [p for p in shaders.iterdir() if p.is_dir() and re.fullmatch(r"world-?\d+", p.name)]:
        for f in folder.iterdir():
            if f.suffix in STAGES:
                found[f.relative_to(shaders).as_posix()] = f
    return found


def compile_errors(glslang: str, text: str, stage: str, work: Path):
    source = work / f"program.{stage}"
    source.write_text(text, encoding="utf-8")
    result = subprocess.run([glslang, "-S", stage, str(source)], capture_output=True, text=True, errors="replace")
    return result.returncode == 0, [l.split(":", 3)[-1].strip() for l in result.stdout.splitlines() if "ERROR" in l]


def check(pack, archive: Path, jar: str, includes: list, glslang: str) -> list:
    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        original = unpack(archive, tmp / "original")
        patched = unpack(archive, tmp / "patched")
        result = subprocess.run(["java", "-cp", jar, "im.opl.mcme.marker.shaderpacks.ShaderPackPatcher", str(patched)] + includes,
                                capture_output=True, text=True, errors="replace")
        said = (result.stdout + result.stderr).strip()
        took = re.search(r": (.+?) recipe", said.splitlines()[0] if said else "")
        if not took or took.group(1) != pack["recipe"]:
            return [f"the {pack['recipe']} recipe didn't take it: {said[:600]}"]
        print(f"  {said.splitlines()[0]}")
        before, after = programs(original), programs(patched)
        for name, file in sorted(after.items()):
            text = as_iris(patched, file)
            if name in before and as_iris(original, before[name]) == text:
                continue
            stage = STAGES[file.suffix]
            ok_after, errors_after = compile_errors(glslang, text, stage, tmp)
            ok_before, errors_before = (False, []) if name not in before else compile_errors(glslang, as_iris(original, before[name]), stage, tmp)
            new = [e for e in errors_after if e not in errors_before]
            if not ok_after and (ok_before or new):
                problems.append(f"{name} no longer compiles: " + "; ".join(new[:4]))
            print(f"  {'OK    ' if ok_after or not (ok_before or new) else 'BROKEN'} {name}"
                  + ("" if ok_after else " (fails before the patch too)" if not new else ""))
    return problems


def main():
    parser = argparse.ArgumentParser(description="Check the shader-pack recipes against the packs they're for.")
    parser.add_argument("--jar", required=True, help="The built mod jar.")
    parser.add_argument("--include", action="append", required=True, help="A resource pack's minecraft/shaders/include folder.")
    parser.add_argument("--glslang", default=shutil.which("glslang") or shutil.which("glslangValidator"), help="glslang's executable.")
    parser.add_argument("--cache", default=str(Path.home() / ".cache" / "mcme-recipes"), help="Where downloaded packs are kept.")
    parser.add_argument("--only", help="Only the pack of this name.")
    args = parser.parse_args()
    if not args.glslang:
        sys.exit("glslang wasn't found: pass --glslang")
    packs = json.loads((HERE / "packs.json").read_text(encoding="utf-8"))["packs"]
    github = os.environ.get("GITHUB_ACTIONS") == "true"
    failed = 0
    for pack in packs:
        if args.only and pack["name"] != args.only:
            continue
        print(pack["name"])
        try:
            problems = check(pack, download(pack, Path(args.cache)), args.jar, args.include, args.glslang)
        except Exception as e:
            problems = [f"couldn't check it: {e}"]
        for problem in problems:
            print(f"::error::{pack['name']}: {problem}" if github else f"  PROBLEM {problem}")
        failed += bool(problems)
    print(f"{failed} of {len(packs) if not args.only else 1} packs failed" if failed else "every recipe takes its pack, and breaks nothing")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
