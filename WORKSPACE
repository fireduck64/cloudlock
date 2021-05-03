

load("@bazel_tools//tools/build_defs/repo:git.bzl", "git_repository")
load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

git_repository(
    name = "rules_jvm_external",
    remote = "https://github.com/bazelbuild/rules_jvm_external",
    commit = "9aec21a7eff032dfbdcf728bb608fe1a02c54124",
    shallow_since = "1577467222 -0500"
)

load("@rules_jvm_external//:defs.bzl", "maven_install")


git_repository(
  name = "duckutil",
  remote = "https://github.com/fireduck64/duckutil",
  commit = "ce92417ce507b12d3286e16050223aa9ba3b527b",
  shallow_since = "1618431821 -0700",
)


maven_install(
    artifacts = [
        "junit:junit:4.12",
        "com.google.guava:guava:28.1-jre",
        "com.thetransactioncompany:jsonrpc2-server:1.11",
        "com.amazonaws:aws-java-sdk:1.11.1009",
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
        "https://maven.google.com",
    ],
    #maven_install_json = "//:maven_install.json",
)
# After changes run:
# bazel run @unpinned_maven//:pin

load("@maven//:defs.bzl", "pinned_maven_install")
pinned_maven_install()

