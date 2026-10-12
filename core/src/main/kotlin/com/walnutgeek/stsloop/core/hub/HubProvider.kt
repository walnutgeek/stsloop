package com.walnutgeek.stsloop.core.hub

import com.walnutgeek.stsloop.core.grammar.BucketConfig
import com.walnutgeek.stsloop.core.grammar.BucketRegistry
import com.walnutgeek.stsloop.core.grammar.CaptureProvider
import com.walnutgeek.stsloop.core.grammar.Descriptor
import com.walnutgeek.stsloop.core.grammar.Primitive
import com.walnutgeek.stsloop.core.grammar.Provider

/**
 * The hub's Providers, as the phone sees them: the Buckets of one
 * `descriptors` snapshot, live from the hub or from the cache ([HubStore]).
 * Each keeps only the Primitives the phone knows ([Primitive.of]); a Bucket
 * whose Primitives the phone knows none of can still be named and focused.
 */
class HubProvider(private val snapshot: Descriptors?) : Provider {
    override fun descriptors(): List<Descriptor> = snapshot?.buckets.orEmpty().map { d ->
        Descriptor(d.name, d.aliases, d.primitives.mapNotNull(Primitive::of).toSet(), hub = true)
    }

    companion object {
        /** Every Bucket the phone can address: the capture Buckets of [config], then the hub's of [snapshot]. */
        fun registry(config: BucketConfig, snapshot: Descriptors?) =
            BucketRegistry(listOf(CaptureProvider(config), HubProvider(snapshot)))
    }
}
