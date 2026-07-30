// A cacheable task whose only defect is that it names its own output by absolute path, which is the
// shape every verifier task in this repository had. Exec puts `args` into the build cache key, so the
// entry this stores is private to the checkout that produced it and every other path is a permanent
// miss. The build is correct, the cache is enabled, and nothing reports the waste — which is why
// verify-build-cache-portability.sh has to.
val report = layout.buildDirectory.file("reports/portability/report.txt")
val reportPath = report.get().asFile.absolutePath
val reportDirectory = report.get().asFile.parentFile.absolutePath

tasks.register<Exec>("probe") {
    inputs.property("subject", "absolute-path-argument")
    outputs.file(report).withPropertyName("report")
    outputs.cacheIf("the probe writes one fixed line") { true }
    workingDir = projectDir
    executable = "bash"
    args("-c", "mkdir -p '$reportDirectory' && printf 'ok\\n' > '$reportPath'")
}
