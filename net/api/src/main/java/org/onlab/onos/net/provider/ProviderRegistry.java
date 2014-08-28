package org.onlab.onos.net.provider;

import java.util.Set;

/**
 * Registry for tracking information providers with the core.
 *
 * @param <P> type of the information provider
 * @param <S> type of the provider service
 */
public interface ProviderRegistry<P extends Provider, S extends ProviderService<P>> {

    /**
     * Registers the supplied provider with the core.
     *
     * @param provider provider to be registered
     * @return provider service for injecting information into core
     * @throws java.lang.IllegalArgumentException if the provider is registered already
     */
    S register(P provider);

    /**
     * Unregisters the supplied provider. As a result the previously issued
     * provider service will be invalidated and any subsequent invocations
     * of its methods may throw {@link java.lang.IllegalStateException}.
     * <p/>
     * Unregistering a provider that has not been previously registered results
     * in a no-op.
     *
     * @param provider provider to be unregistered
     */
    void unregister(P provider);

    /**
     * Returns a set of currently registered provider identities.
     *
     * @return set of provider identifiers
     */
    Set<ProviderId> getProviders();

}
