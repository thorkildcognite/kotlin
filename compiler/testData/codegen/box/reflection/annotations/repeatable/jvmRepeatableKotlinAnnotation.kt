// !LANGUAGE: +RepeatableAnnotations
// TARGET_BACKEND: JVM_IR
// FULL_JDK
// WITH_REFLECT

@java.lang.annotation.Repeatable(A.Container::class)
annotation class A(val value: String) {
    annotation class Container(val value: Array<A>)
}

@A("O")
@A("")
@A("K")
fun f() {}

fun box(): String {
    val ac = ::f.annotations.single() as A.Container
    return ac.value.asList().fold("") { acc, it -> acc + it.value }
}
