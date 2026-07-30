// A verifier task with fully relative arguments and no declared outputs, which is the shape
// nativeAbiLayoutTest, verifyRuntimeApi and verifyKotlinApi had. Portable arguments are not enough on
// their own: with nothing declared, Gradle can neither consider the task up to date nor store anything
// for it, so it re-executes on every run of every checkout and nothing reports the waste.
tasks.register<Exec>("probe") {
    inputs.property("subject", "undeclared-output")
    outputs.cacheIf("the probe writes one fixed line") { true }
    workingDir = projectDir
    executable = "bash"
    args(
        "-c",
        "mkdir -p build/reports/portability && printf 'ok\\n' > build/reports/portability/report.txt",
    )
}
