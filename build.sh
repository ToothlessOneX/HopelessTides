#!/bin/bash
# Hopeless Tides build: compile (official names) -> jar -> remap to SRG
set -e
cd /home/claude/tides
V=$(grep -m1 '^version=' src/main/resources/META-INF/mods.toml | cut -d'"' -f2)
L=/mnt/user-data/uploads/libraries
JV=$(grep -o 'VERSION = "[0-9.]*"' src/main/java/com/hopeless/tides/HopelessTides.java | cut -d'"' -f2)
[ "$JV" = "$V" ] || { echo "VERSION MISMATCH: mods.toml $V vs code $JV"; exit 1; }
rm -rf build/classes && mkdir -p build/classes
if ! javac --release 17 -nowarn -proc:none -cp "$(cat build/cp.txt)$(cat build/cp-extra.txt 2>/dev/null)" -d build/classes $(find src/main/java -name '*.java') 2> build/javac.err; then grep -v "Picked\|^Note" build/javac.err | head -40; echo "COMPILE FAILED - no jar built"; exit 1; fi
rm -f build/hd-official.jar
jar cfm build/hd-official.jar build/manifest.txt -C build/classes . -C src/main/resources .
IFS=':' read -ra CP <<< "$(cat build/cp.txt)$(cat build/cp-extra.txt 2>/dev/null)"
LIBS_ARR=(--lib /home/claude/bridge/lib/mc-official.jar --lib /home/claude/bridge/lib/forgeclient-official.jar)
for j in "${CP[@]}"; do [ -f "$j" ] && LIBS_ARR+=(--lib "$j"); done
java -jar $L/net/minecraftforge/ForgeAutoRenamingTool/0.1.22/ForgeAutoRenamingTool-0.1.22-all.jar \
  --input build/hd-official.jar --output build/hopelesstides-$V.jar --map /home/claude/bridge/tools/off2srg_fixed.tsrg "${LIBS_ARR[@]}" 2>&1 | grep -v Picked | tail -2
unzip -p build/hopelesstides-$V.jar META-INF/MANIFEST.MF | grep -q "MixinConfigs: hopelesstides.mixins.json" || { echo "MANIFEST LOST MixinConfigs"; exit 1; }
echo "built build/hopelesstides-$V.jar"
