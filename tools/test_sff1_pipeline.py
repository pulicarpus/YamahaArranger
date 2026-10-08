#!/usr/bin/env python3
"""Run actual S1 Kotlin/JUnit tests on plain JVM17, without Android/NDK/APK.

Production decoders/sequencer/transformer are compiled unchanged. Boundary
facades live outside Android source sets and have no playback implementation.
Dependency bytes are SHA-pinned; downloads require --fetch-dependencies.
"""
from f04_source_guard import historical_bytes, pipeline_expected
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys

from audit_sff1_corpus import ROOT, ConformanceError, file_sha, production_identity, require

S1_CLASS="com.yourapp.yamahaarranger.arranger.Sff1ProductionPipelineRegressionTest"
EXISTING=["CasmNoteTransformerTest","StylePartPresenceRegressionTest","StyleExpressionRegressionTest",
          "StyleMixFidelityRegressionTest","StyleChordDiagnosticRegressionTest"]


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dependencies",type=Path,default=ROOT/"build/sff1-jvm/dependencies")
    parser.add_argument("--fetch-dependencies",action="store_true",help="fetch missing SHA-pinned jars from official Maven Central")
    parser.add_argument("--output",type=Path,default=ROOT/"build/sff1-jvm")
    parser.add_argument("--existing-regressions",action="store_true",help="also run five relevant existing JUnit classes")
    parser.add_argument("--s3-protocols",type=Path,help="full 503 generated semantic matrices for S3 host test")
    parser.add_argument("--s3",action="store_true",help="also run CASM preservation tests")
    parser.add_argument("--s4",action="store_true",help="also run read-only root-selection evidence tests")
    parser.add_argument("--s5",action="store_true",help="also run read-only ownership investigation")
    parser.add_argument("--record-s5",action="store_true",help="initial S5 trace observation only; refuses existing S5 digest")
    parser.add_argument("--s2",action="store_true",help="also run dialect metadata tests; S1 captures stay pinned")
    parser.add_argument("--record-digests",action="store_true",help="create initial expected capture digest; never overwrite")
    parser.add_argument("--f04-baseline",action="store_true",help="compile the exact pinned pre-F04 sequencer; never modify working source")
    parser.add_argument("--f04",action="store_true",help="also execute isolated F04 routing controls")
    args=parser.parse_args(argv)
    require(shutil.which("java") is not None,"JVM17 required")
    production_identity()
    deps=args.dependencies.resolve();deps.mkdir(parents=True,exist_ok=True)
    lock=json.loads((ROOT/"tests/fixtures/sff1_jvm_dependencies.json").read_text())
    jars=[]
    for item in lock["dependencies"]:
        path=deps/item["filename"]
        if not path.exists():
            require(args.fetch_dependencies,f"missing dependency {path}; supply --dependencies or explicitly --fetch-dependencies")
            subprocess.run(["curl","-fsSL","--max-time","120",item["url"],"-o",str(path)],check=True)
        require(file_sha(path)==item["sha256"],f"dependency SHA mismatch: {path.name}")
        jars.append(path)
    require((ROOT/"app/src/test/resources/sff1_main_d_native.tsv").exists(),"verify corpus/generate native golden fixture first")
    expected=ROOT/"app/src/test/resources/sff1_pipeline_digests.tsv"
    require(not expected.exists() if args.record_digests else expected.exists(),
            "initial --record-digests never overwrites expected; normal regression requires expected digests")
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=True)
    jar=output/"sff1-jvm-tests.jar"
    classpath=":".join(map(str,jars))
    baseline_source=output/"baseline-StyleSequencer.kt"
    if args.f04_baseline:
        baseline_source.write_bytes(historical_bytes("app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt"))
    sources=[ROOT/"app/src/main/java/com/yourapp/style/StyleModel.kt",
             ROOT/"app/src/main/java/com/yourapp/yamahaarranger/style/StyleRepository.kt"]
    sources+=sorted((ROOT/"app/src/main/java/com/yourapp/chord").glob("*.kt"))
    arranger=ROOT/"app/src/main/java/com/yourapp/yamahaarranger/arranger"
    sources += [arranger/(name+".kt") for name in ("StyleSequencer","CasmNoteTransformer","StyleAudioPathDiagnostic","StylePartPresence","ChordChangeDiagnostic")]
    sources+=sorted((ROOT/"tests/sff1_jvm_stubs").glob("*.kt"))
    test_root=ROOT/"app/src/test/java/com/yourapp/yamahaarranger/arranger"
    tests=[S1_CLASS]
    sources.append(test_root/"Sff1ProductionPipelineRegressionTest.kt")
    if args.s2:
        sources.append(test_root/"SffDialectBoundaryTest.kt")
        tests.append("com.yourapp.yamahaarranger.arranger.SffDialectBoundaryTest")
    if args.s3:
        sources.append(test_root/"SffCasmSemanticPreservationTest.kt")
        tests.append("com.yourapp.yamahaarranger.arranger.SffCasmSemanticPreservationTest")
    if args.s4:
        sources.append(test_root/"SffRootSelectionEvidenceTest.kt")
        tests.append("com.yourapp.yamahaarranger.arranger.SffRootSelectionEvidenceTest")
    if args.s5:
        sources.append(test_root/"F03NoteOwnershipInvestigationTest.kt")
        tests.append("com.yourapp.yamahaarranger.arranger.F03NoteOwnershipInvestigationTest")
        # Compile real external MIDI serializer and real ACMP controller.
        # New facades supply platform/log/audio types only, never decisions.
        replaced={"AudioEngineManager.kt","MidiInputManager.kt","Timber.kt","DebugLog.kt"}
        sources=[p for p in sources if not(p.parent.name=='sff1_jvm_stubs' and p.name in replaced)]
        sources+=sorted((ROOT/'tests/sff5_jvm_stubs').glob('*.kt'))
        sources += [ROOT/'app/src/main/java/com/yourapp/midi/MidiInputManager.kt',ROOT/'app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt',test_root/'F03BoundaryLifecycleInvestigationTest.kt']
        tests.append("com.yourapp.yamahaarranger.arranger.F03BoundaryLifecycleInvestigationTest")
    if args.existing_regressions:
        sources += [test_root/(name+".kt") for name in EXISTING]
        tests += ["com.yourapp.yamahaarranger.arranger."+name for name in EXISTING]
    if args.f04_baseline:
        sources=[baseline_source if p.name=="StyleSequencer.kt" else p for p in sources]
    if args.f04:
        sources.append(test_root/"F04RoutingRegressionTest.kt")
        tests.append("com.yourapp.yamahaarranger.arranger.F04RoutingRegressionTest")
    compile_command=["java","-Xmx1g","-cp",classpath,"org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                     "-no-stdlib","-no-reflect","-nowarn","-jvm-target","17","-classpath",classpath,"-d",str(jar),*map(str,sources)]
    with (output/"compile.log").open("w") as log:
        result=subprocess.run(compile_command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
    if result.returncode:
        print((output/"compile.log").read_text(),file=sys.stderr)
        raise ConformanceError("Kotlin compilation failed; do not alter playback to make harness compile")
    capture=output/"sff1_pipeline_observed.tsv"
    if capture.exists():
        capture.unlink()
    runtime=":".join([str(jar),str(ROOT/"app/src/test/resources"),classpath])
    # Start Mockito's pinned agent explicitly: sandboxed hosts may disallow
    # self-attach. This is JVM test tooling, never an app/production agent.
    command=["java","-Xmx1g",f"-Dsff1.traceDirectory={output / 'traces'}",f"-javaagent:{deps/'byte-buddy-agent.jar'}",f"-Dsff1.output={capture}","-cp",runtime]
    if args.s5:
        command.insert(2,f"-Dsff5.output={output/'ownership-traces.txt'}")
    if args.record_s5:
        require(args.s5 and not (ROOT/'app/src/test/resources/sff5/overlap_dispatch.sha256').exists(),"--record-s5 initial only")
        command.insert(2,"-Dsff5.record=true")
    if args.s3_protocols:
        command.insert(2,f"-Dsff3.protocols={args.s3_protocols.resolve()}")
    if args.record_digests:
        command.insert(2,"-Dsff1.record.observations=true")
    command += ["org.junit.runner.JUnitCore",*tests]
    with (output/"junit.log").open("w") as log:
        result=subprocess.run(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
    print((output/"junit.log").read_text())
    require(result.returncode==0,"S1/relevant JUnit regression failed; expected fixtures must not be auto-updated")
    require(capture.exists(),"JUnit produced no pipeline observation report")
    rows=capture.read_text().splitlines()
    require(len(rows)==20,"expected six synthetic captures + fourteen golden captures")
    require(len({row.split("\t")[0] for row in rows})==20,"duplicate capture identity")
    if args.record_digests:
        expected.write_bytes(capture.read_bytes())
        print("SFF1_INITIAL_DIGESTS_RECORDED: rerun normal strict regression before claiming PASS")
    else:
        require(capture.read_bytes()==(expected.read_bytes() if args.f04_baseline else pipeline_expected()),"complete pipeline digest set differs from selected exact profile")
        print("SFF1_PIPELINE PASS production Kotlin/repository functions=true boundary mocks=true nativeAcceptance/PCM=NOT_MEASURED")
    production_identity()
    return 0


if __name__=="__main__":
    try:
        sys.exit(main())
    except (ConformanceError,subprocess.CalledProcessError,OSError,ValueError) as error:
        print(f"SFF1_PIPELINE FAIL: {error}",file=sys.stderr)
        sys.exit(1)
