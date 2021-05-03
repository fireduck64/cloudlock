package(default_visibility = ["//visibility:public"])

java_library(
  name = "cloudlocklib",
  srcs = glob(["src/**/*.java", "src/*.java"]),
  deps = [
    "@duckutil//:duckutil_lib",
    "@maven//:com_amazonaws_aws_java_sdk",
    "@maven//:com_google_guava_guava",
    "@maven//:com_amazonaws_aws_java_sdk_dynamodb",
    "@maven//:com_amazonaws_aws_java_sdk_core",
    "@maven//:com_amazonaws_aws_java_sdk_cloudwatch",
  ],
)

java_binary(
  name = "CloudLock",
  main_class = "duckutil.cloudlock.CloudLock",
  runtime_deps = [
    ":cloudlocklib",
  ],
)

