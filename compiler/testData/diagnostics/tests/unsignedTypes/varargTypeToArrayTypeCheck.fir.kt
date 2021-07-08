
fun ubyte(vararg a: UByte): UByteArray = <!EXPERIMENTAL_API_USAGE!>a<!>
fun ushort(vararg a: UShort): UShortArray = <!EXPERIMENTAL_API_USAGE!>a<!>
fun uint(vararg a: UInt): UIntArray = <!EXPERIMENTAL_API_USAGE!>a<!>
fun ulong(vararg a: ULong): ULongArray = <!EXPERIMENTAL_API_USAGE!>a<!>

fun rawUInt(vararg a: UInt): IntArray = <!EXPERIMENTAL_API_USAGE, RETURN_TYPE_MISMATCH!>a<!>
