// !LANGUAGE: +RepeatableAnnotations
// TARGET_BACKEND: JVM_IR
// FULL_JDK
// WITH_REFLECT

import kotlin.reflect.KAnnotatedElement

fun check(element: KAnnotatedElement) {
    val aa = element.annotations
    if (aa.any { it !is A })
        throw AssertionError("Fail 1 $element: $aa")
    if (aa.fold("") { acc, it -> acc + (it as A).value } != "OK")
        throw AssertionError("Fail 2 $element: $aa")
}

@Repeatable
annotation class A(val value: String)

@A("O") @A("") @A("K")
fun f() {}

@A("") @A("O") @A("K")
var p = 1

@A("O") @A("K") @A("")
class Z

fun box(): String {
    check(::f)
    check(::p)
    check(Z::class)
    return "OK"
}
