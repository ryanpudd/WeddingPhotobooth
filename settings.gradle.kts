rootProject.name = "WeddingPhotobooth"
gradle.extra["uvccRoot"] = "C:/workspace/repos/github.com/saki4510t/UVCCamera"

include(":app")
// Can't do this as UVCCamera is too old
//includeBuild("C:/workspace/repos/github.com/saki4510t/UVCCamera")
