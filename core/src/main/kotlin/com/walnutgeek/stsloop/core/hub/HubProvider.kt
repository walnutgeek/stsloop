package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.grammar.BucketCommand
import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.grammar.BucketRegistry
import com.walnutgeek.stsloop.core.grammar.CaptureProvider
import com.walnutgeek.stsloop.core.grammar.Descriptor
import com.walnutgeek.stsloop.core.grammar.Primitive
import com.walnutgeek.stsloop.core.grammar.Provider

/**
 * The hub's Providers, as the phone sees them: the Buckets of one
 * `descriptors` snapshot, live from the hub or from the cache ([HubStore]).
 * Each keeps only the Primitives the phone knows ([Primitive.of]), and only
 * the commands those run; a Bucket whose Primitives the phone knows none of
 * can still be named and focused. A Bucket that names a Primitive the phone
 * does not know is [Descriptor.degraded]: of its commands, only its
 * `request` ones are kept (ADR-0006).
 */
class HubProvider(private val snapshot: Descriptors?) : Provider {
    override fun descriptors(): List<Descriptor> = snapshot?.buckets.orEmpty().map { d ->
        val degraded = d.primitives.any { Primitive.of(it) == null }
        val commands = d.commands.mapNotNull { c ->
            val primitive = Primitive.of(c.primitive) ?: return@mapNotNull null
            if (degraded && primitive != Primitive.REQUEST) return@mapNotNull null
            BucketCommand(c.id, c.phrases, primitive, c.anywhere, c.replyWithinMs)
        }
        Descriptor(
            d.name, d.aliases, d.primitives.mapNotNull(Primitive::of).toSet(), hub = true,
            id = d.id, commands = commands, degraded = degraded, dialog = d.dialog,
        )
    }

    companion object {
        /** Every Bucket the phone can address: the capture Buckets of [config], then the hub's of [snapshot]. */
        fun registry(config: BucketConfig, snapshot: Descriptors?) =
            BucketRegistry(listOf(CaptureProvider(config), HubProvider(snapshot)))
    }
}
