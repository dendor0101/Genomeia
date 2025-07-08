package io.github.some_example_name.attempts.utils

import kotlin.Pair

public data class Pair<out A, out B>(
    public val first: A,
    public val second: B
) {

    constructor() : this(Unit as A, Any() as B)

    public override fun toString(): String = "(\n   $first, \n" +
        "   $second)"
}
