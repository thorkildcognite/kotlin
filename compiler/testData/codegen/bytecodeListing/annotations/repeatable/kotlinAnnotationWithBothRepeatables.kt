// !LANGUAGE: +RepeatableAnnotations
// TARGET_BACKEND: JVM_IR
// WITH_RUNTIME
// FULL_JDK

package test

@Repeatable
@java.lang.annotation.Repeatable(As::class)
annotation class A(val value: String)

annotation class As(val value: Array<A>)

@A("a1")
@A("a2")
class Z
