
plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    testCompile(kotlinStdlib("jdk8"))
    testCompile(project(":kotlin-scripting-compiler"))
    testCompile(project(":core:descriptors"))
    testCompile(project(":core:descriptors.jvm"))
    testCompile(project(":core:deserialization"))
    testCompile(project(":compiler:util"))
    testCompile(project(":compiler:tests-mutes"))
    testCompile(project(":compiler:backend"))
    testCompile(project(":compiler:ir.tree.impl"))
    testCompile(project(":compiler:fir:tree"))
    testCompile(project(":compiler:fir:raw-fir:psi2fir"))
    testCompile(project(":compiler:fir:raw-fir:light-tree2fir"))
    testCompile(project(":compiler:fir:fir2ir"))
    testCompile(project(":compiler:fir:jvm"))
    testCompile(project(":compiler:fir:fir2ir:jvm-backend"))
    testCompile(project(":compiler:fir:fir-serialization"))
    testCompile(project(":compiler:fir:fir-deserialization"))
    testCompile(project(":compiler:fir:cones"))
    testCompile(project(":compiler:fir:resolve"))
    testCompile(project(":compiler:fir:checkers"))
    testCompile(project(":compiler:fir:checkers:checkers.jvm"))
    testCompile(project(":compiler:fir:java"))
    testCompile(project(":compiler:fir:entrypoint"))
    testCompile(project(":compiler:ir.ir2cfg"))
    testCompile(project(":compiler:frontend"))
    testCompile(project(":compiler:frontend.java"))
    testCompile(project(":compiler:util"))
    testCompile(project(":compiler:cli-common"))
    testCompile(project(":compiler:cli"))
    testCompile(project(":compiler:cli-js"))
    testCompile(project(":compiler:light-classes"))
    testCompile(project(":compiler:serialization"))
    testCompile(project(":kotlin-preloader"))
    testCompile(project(":compiler:cli-common"))
    testCompile(project(":daemon-common"))
    testCompile(project(":daemon-common-new"))
    testCompile(project(":js:js.serializer"))
    testCompile(project(":js:js.frontend"))
    testCompile(project(":js:js.translator"))
    testCompile(project(":native:frontend.native"))
    testCompileOnly(project(":plugins:android-extensions-compiler"))
    testApi(projectTests(":generators:test-generator"))
    testCompile(projectTests(":compiler:tests-compiler-utils"))
    testCompile(project(":kotlin-test:kotlin-test-jvm"))
    testCompile(projectTests(":compiler:tests-common-jvm6"))
    testCompile(project(":kotlin-scripting-compiler-impl"))
    testCompile(projectTests(":compiler:test-infrastructure-utils"))
    testCompile(commonDep("junit:junit"))
    testCompile(commonDep("com.android.tools:r8"))
    testCompileOnly(project(":kotlin-reflect-api"))
    testCompileOnly(toolsJar())
    testCompileOnly(intellijCoreDep()) { includeJars("intellij-core") }
    testCompile(intellijDep()) { includeJars("intellij-deps-fastutil-8.4.1-4") }
    testCompile(intellijDep()) {
        includeJars(
            "guava",
            "trove4j",
            "asm-all",
            "log4j",
            "jdom",
            "jna",
            rootProject = rootProject
        )
        isTransitive = false
    }

    testApiJUnit5()
}

tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinCompile<*>> {
    kotlinOptions {
        freeCompilerArgs += "-Xinline-classes"
    }
}

sourceSets {
    "main" { }
    "test" { projectDefault() }
}

testsJar {}
