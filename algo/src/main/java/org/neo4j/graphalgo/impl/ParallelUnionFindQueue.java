package org.neo4j.graphalgo.impl;

import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.core.utils.ParallelUtil;
import org.neo4j.graphalgo.core.utils.dss.DisjointSetStruct;
import org.neo4j.graphdb.Direction;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * parallel UnionFind using ExecutorService only.
 * <p>
 * Algorithm based on the idea that DisjointSetStruct can be built using
 * just a partition of the nodes which then can be merged pairwise.
 * <p>
 * The implementation is based on a queue which acts as a buffer
 * for each computed DSS. As long as there are more elements on
 * the queue the algorithm takes two, merges them and adds its
 * result to the queue until only 1 element remains.
 *
 * @author mknblch
 */
public class ParallelUnionFindQueue extends GraphUnionFindAlgo<Graph, DisjointSetStruct, ParallelUnionFindQueue> {

    private final ExecutorService executor;
    private final int nodeCount;
    private final int batchSize;
    private final LinkedBlockingQueue<DisjointSetStruct> queue;
    private final List<Future<?>> futures;

    public static Function<Graph, ParallelUnionFindQueue> of(ExecutorService executor, int minBatchSize, int concurrency) {
        return graph -> new ParallelUnionFindQueue(
                graph,
                executor,
                minBatchSize,
                concurrency);
    }

    /**
     * initialize parallel UF
     */
    public ParallelUnionFindQueue(Graph graph, ExecutorService executor, int minBatchSize, int concurrency) {
        super(graph);
        this.executor = executor;
        nodeCount = Math.toIntExact(graph.nodeCount());
        this.batchSize = ParallelUtil.adjustBatchSize(nodeCount, concurrency, minBatchSize);
        queue = new LinkedBlockingQueue<>();
        futures = new ArrayList<>();
    }

    @Override
    public DisjointSetStruct compute() {
        final int steps = Math.floorDiv(nodeCount, batchSize) - 1;
        for (int i = 0; i < nodeCount; i += batchSize) {
            futures.add(executor.submit(new UnionFindTask(i)));
        }
        for (int i = steps - 1; i >= 0; i--) {
            futures.add(executor.submit(() -> {
                final DisjointSetStruct a;
                final DisjointSetStruct b;
                try {
                    a = queue.take();
                    b = queue.take();
                    queue.add(a.merge(b));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }));
        }

        await();

        return getStruct();
    }

    private void await() {
        ParallelUtil.awaitTermination(futures);
    }

    @Override
    public DisjointSetStruct compute(double threshold) {
        throw new IllegalArgumentException("Not yet implemented");
    }

    private DisjointSetStruct getStruct() {
        try {
            return queue.take();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return null;
    }

    private class UnionFindTask implements Runnable {

        protected final int offset;
        protected final int end;

        UnionFindTask(int offset) {
            this.offset = offset;
            this.end = Math.min(offset + batchSize, nodeCount);
        }

        @Override
        public void run() {
            final DisjointSetStruct struct = new DisjointSetStruct(nodeCount).reset();
            for (int node = offset; node < end; node++) {
                graph.forEachRelationship(node, Direction.OUTGOING, (sourceNodeId, targetNodeId, relationId) -> {
                    if (!struct.connected(sourceNodeId, targetNodeId)) {
                        struct.union(sourceNodeId, targetNodeId);
                    }
                    return true;
                });
            }
            getProgressLogger().logProgress((end - 1.0) / (nodeCount - 1.0));
            queue.add(struct);
        }
    }
}
