package dev.crec.beacon.utils

import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder

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

inline fun <S, T: ArgumentBuilder<S, T>> ArgumentBuilder<S, T>.literal(
    literal: String,
    builder: LiteralArgumentBuilder<S>.() -> Unit = { }
): LiteralArgumentBuilder<S> {
    val argument = LiteralArgumentBuilder.literal<S>(literal)
    argument.builder()
    this.then(argument)
    return argument
}

inline fun <A, S, T: ArgumentBuilder<S, T>> ArgumentBuilder<S, T>.argument(
    name: String,
    type: ArgumentType<A>,
    builder: RequiredArgumentBuilder<S, A>.() -> Unit = { }
): RequiredArgumentBuilder<S, A> {
    val first = RequiredArgumentBuilder.argument<S, A>(name, type)
    first.builder()
    this.then(first)
    return first
}