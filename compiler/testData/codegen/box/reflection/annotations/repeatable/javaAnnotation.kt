// !LANGUAGE: +RepeatableAnnotations
// TARGET_BACKEND: JVM_IR
// FULL_JDK
// WITH_REFLECT
// FILE: box.kt

@J("O")
@J("")
@J("K")
fun f() {}

fun box(): String {
    val jc = ::f.annotations.single() as J.Container
    return jc.value.asList().fold("") { acc, it -> acc + it.value }
}

// FILE: J.java

import java.lang.annotation.*;

@Repeatable(J.Container.class)
@Retention(RetentionPolicy.RUNTIME)
public @interface J {
    String value();

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Container {
        J[] value();
    }
}
