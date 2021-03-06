package org.neo4j.graphalgo.api;

/**
 * Iterator for both-relations based on nodeId.
 * <p>
 * TODO: remove interface
 *
 * @author mknblch
 */
public interface BothRelationshipIterator {

    void forEachRelationship(int nodeId, RelationshipConsumer consumer);
}
