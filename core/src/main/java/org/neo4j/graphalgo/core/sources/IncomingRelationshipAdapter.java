package org.neo4j.graphalgo.core.sources;

import org.neo4j.graphalgo.api.IncomingRelationshipIterator;
import org.neo4j.graphalgo.api.RelationshipConsumer;
import org.neo4j.graphalgo.core.utils.container.RelationshipContainer;


/**
 * Adapter class for RelationshipContainer -> IncomingRelationIterator
 *
 * @author mknblch
 */
public class IncomingRelationshipAdapter implements IncomingRelationshipIterator {

    private final RelationshipContainer container;

    public IncomingRelationshipAdapter(RelationshipContainer container) {
        this.container = container;
    }

    @Override
    public void forEachIncoming(int nodeId, RelationshipConsumer consumer) {
        container.forEach(nodeId, consumer);
    }
}
