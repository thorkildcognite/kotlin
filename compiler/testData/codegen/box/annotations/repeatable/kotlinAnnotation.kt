// !LANGUAGE: +RepeatableAnnotations
// TARGET_BACKEND: JVM_IR
// WITH_RUNTIME
// FULL_JDK
// FILE: box.kt

@Repeatable
annotation class A(val value: String)

@A("O")
@A("")
@A("K")
class Z

fun box(): String {
    val annotations = Z::class.java.annotations.filter { it.annotationClass != Metadata::class }
    val aa = annotations.singleOrNull() ?: return "Fail 1: $annotations"

    val a = ContainerSupport.load(aa)
    if (a.size != 3) return "Fail 2: $a"

    val bytype = Z::class.java.getAnnotationsByType(A::class.java)
    if (a.toList() != bytype.toList()) return "Fail 3: ${a.toList()} != ${bytype.toList()}"

    return a.fold("") { acc, it -> acc + it.value }
}

// FILE: ContainerSupport.java

import java.lang.annotation.Annotation;

public class ContainerSupport {
    public static A[] load(Annotation container) {
        return ((A.Container) container).value();
    }
}
