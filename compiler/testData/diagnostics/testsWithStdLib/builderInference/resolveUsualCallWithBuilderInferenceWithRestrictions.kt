// !DIAGNOSTICS: -UNUSED_PARAMETER
// ALLOW_KOTLIN_PACKAGE
// !WITH_NEW_INFERENCE
// FILE: annotation.kt

package kotlin

annotation class BuilderInference

// FILE: test.kt

class Builder<T> {
    fun add(t: T) {}
}

fun <S> build(@BuilderInference g: Builder<S>.() -> Unit): List<S> = TODO()
fun <S> wrongBuild(g: Builder<S>.() -> Unit): List<S> = TODO()

fun <S> Builder<S>.extensionAdd(s: S) {}

@BuilderInference
fun <S> Builder<S>.safeExtensionAdd(s: S) {}

val member = build {
    add(42)
}

val memberWithoutAnn = <!NEW_INFERENCE_NO_INFORMATION_FOR_PARAMETER{NI}, TYPE_INFERENCE_NO_INFORMATION_FOR_PARAMETER{OI}!>wrongBuild<!> {
    add(42)
}

val extension = <!COULD_BE_INFERRED_ONLY_WITH_UNRESTRICTED_BUILDER_INFERENCE, NEW_INFERENCE_NO_INFORMATION_FOR_PARAMETER{OI}, TYPE_INFERENCE_NO_INFORMATION_FOR_PARAMETER{OI}!>build<!> {
    extensionAdd("foo")
}

val safeExtension = build {
    safeExtensionAdd("foo")
}
