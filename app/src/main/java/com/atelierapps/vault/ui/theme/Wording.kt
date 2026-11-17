package com.atelierapps.vault.ui.theme

/**
 * "1 item" / "3 items". On-screen counts used to read "3 item(s)", which did
 * more than anything to make the app look unfinished; counts go through here.
 * Named to avoid shadowing by the many locals called `items` / `count`.
 */
fun plural(n: Int, one: String, many: String = one + "s"): String =
    if (n == 1) "1 $one" else "$n $many"

fun itemCount(n: Int): String = plural(n, "item")
