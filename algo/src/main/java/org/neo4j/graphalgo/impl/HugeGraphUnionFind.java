package org.neo4j.graphalgo.impl;

import org.neo4j.graphalgo.api.HugeGraph;
import org.neo4j.graphalgo.api.HugeRelationshipConsumer;
import org.neo4j.graphalgo.core.utils.ProgressLogger;
import org.neo4j.graphalgo.core.utils.dss.DisjointSetStruct;
import org.neo4j.graphalgo.core.utils.paged.AllocationTracker;
import org.neo4j.graphalgo.core.utils.paged.HugeDisjointSetStruct;
import org.neo4j.graphdb.Direction;

/**
 * Sequential UnionFind:
 * <p>
 * The algorithm computes sets of weakly connected components.
 * <p>
 * The impl. is based on the {@link DisjointSetStruct}. It iterates over all relationships once
 * within a single forEach loop and adds each source-target pair to the struct. Therefore buffering
 * would introduce an overhead (a SingleRun-RelationshipIterable might be used here).
 * <p>
 * There are 2 different methods for computing the component-sets. compute() calculates all weakly
 * components regardless of the actual weight of the relationship while compute(threshold:double)
 * on the other hand only takes the transition into account if the weight exceeds the threshold value.
 *
 * @author mknblch
 */
public class HugeGraphUnionFind extends GraphUnionFindAlgo<HugeGraph, HugeDisjointSetStruct, HugeGraphUnionFind> {

    private HugeDisjointSetStruct dss;
    private final long nodeCount;
    private HugeRelationshipConsumer unrestricted;

    public HugeGraphUnionFind(
            HugeGraph graph,
            AllocationTracker tracker) {
        super(graph);
        this.graph = graph;
        nodeCount = graph.nodeCount();
        this.dss = new HugeDisjointSetStruct(nodeCount, tracker);
        unrestricted = (source, target) -> {
            dss.union(source, target);
            return true;
        };
    }

    /**
     * compute unions of connected nodes
     *
     * @return a DSS
     */
    @Override
    public HugeDisjointSetStruct compute() {
        return compute(unrestricted);
    }

    /**
     * compute unions if relationship weight exceeds threshold
     *
     * @param threshold the minimum threshold
     * @return a DSS
     */
    @Override
    public HugeDisjointSetStruct compute(final double threshold) {
        return compute(new WithThreshold(threshold));
    }

    @Override
    public HugeGraphUnionFind release() {
        dss = null;
        unrestricted = null;
        return super.release();
    }

    private HugeDisjointSetStruct compute(HugeRelationshipConsumer consumer) {
        dss.reset();
        final ProgressLogger progressLogger = getProgressLogger();
        graph.forEachNode((long node) -> {
            if (!running()) {
                return false;
            }
            graph.forEachRelationship(node, Direction.OUTGOING, consumer);
            progressLogger.logProgress((double) node / (nodeCount - 1));
            return true;
        });
        return dss;
    }

    private final class WithThreshold implements HugeRelationshipConsumer {
        private final double threshold;

        private WithThreshold(final double threshold) {
            this.threshold = threshold;
        }

        @Override
        public boolean accept(
                final long source,
                final long target) {
            double weight = graph.weightOf(source, target);
            if (weight >= threshold) {
                dss.union(source, target);
            }
            return true;
        }
    }
}
