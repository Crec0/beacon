package dev.crec.beacon.utils

import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder

inline fun <E, S, T: ArgumentBuilder<S, T>> ArgumentBuilder<S, T>.option(
    options: Iterable<E>,
    stringifier: (E) -> String = { it.toString() },
    builder: LiteralArgumentBuilder<S>.(E) -> Unit = { }
): ArgumentBuilder<S, T> {
    for (option in options) {
        val name = stringifier.invoke(option)
        val literal = LiteralArgumentBuilder.literal<S>(name)
        literal.builder(option)
        this.then(literal)
    }
    return this
}

inline fun <reified E: Enum<E>, S, T: ArgumentBuilder<S, T>> ArgumentBuilder<S, T>.option(
    stringifier: (E) -> String = { it.name.lowercase() },
    builder: LiteralArgumentBuilder<S>.(E) -> Unit = { }
): ArgumentBuilder<S, T> {
    return this.option(enumValues<E>().toList(), stringifier, builder)
}