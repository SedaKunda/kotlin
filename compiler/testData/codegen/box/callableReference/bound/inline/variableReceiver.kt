inline fun go(f: () -> String) = f()

fun String.id(): String = this

fun box(): String {
    var x: String = "OK"
    val result = go(x::id)
    x = "FAIL"
    return result
}
