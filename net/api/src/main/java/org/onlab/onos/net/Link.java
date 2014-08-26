package org.onlab.onos.net;

import org.onlab.onos.net.provider.Provided;

/**
 * Abstraction of a network infrastructure link.
 */
public interface Link extends Provided { // TODO: Also should extend graph Edge once the graph module is checked in

    /**
     * Coarse representation of the link type.
     */
    public enum Type {
        /**
         * Signifies that this is a direct single-segment link.
         */
        DIRECT,

        /**
         * Signifies that this link is potentially comprised from multiple
         * underlying segments or hops, e.g. optical links, tunnel links,
         * multi-hop links spanning 'dark' switches
         */
        INDIRECT
    }

    /**
     * Returns the link source connection point.
     *
     * @return link source connection point
     */
    ConnectPoint src();

    /**
     * Returns the link destination connection point.
     *
     * @return link destination connection point
     */
    ConnectPoint dst();

    // LinkInfo info(); // Additional link information / decorations

}
