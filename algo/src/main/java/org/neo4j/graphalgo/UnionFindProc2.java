package org.neo4j.graphalgo;

import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.api.HugeGraph;
import org.neo4j.graphalgo.core.GraphLoader;
import org.neo4j.graphalgo.core.ProcedureConfiguration;
import org.neo4j.graphalgo.core.ProcedureConstants;
import org.neo4j.graphalgo.core.utils.Pools;
import org.neo4j.graphalgo.core.utils.ProgressLogger;
import org.neo4j.graphalgo.core.utils.ProgressTimer;
import org.neo4j.graphalgo.core.utils.TerminationFlag;
import org.neo4j.graphalgo.core.utils.dss.DisjointSetStruct;
import org.neo4j.graphalgo.core.utils.paged.AllocationTracker;
import org.neo4j.graphalgo.core.utils.paged.HugeDisjointSetStruct;
import org.neo4j.graphalgo.core.write.DisjointSetStructTranslator;
import org.neo4j.graphalgo.core.write.Exporter;
import org.neo4j.graphalgo.core.write.HugeDisjointSetStructTranslator;
import org.neo4j.graphalgo.impl.GraphUnionFind;
import org.neo4j.graphalgo.impl.HugeGraphUnionFind;
import org.neo4j.graphalgo.impl.HugeParallelUnionFindQueue;
import org.neo4j.graphalgo.impl.ParallelUnionFindQueue;
import org.neo4j.graphalgo.results.UnionFindResult;
import org.neo4j.graphdb.Direction;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.logging.Log;
import org.neo4j.procedure.*;

import java.util.Map;
import java.util.stream.Stream;

/**
 * @author mknblch
 */
public class UnionFindProc2 {

    public static final String CONFIG_THRESHOLD = "threshold";
    public static final String CONFIG_CLUSTER_PROPERTY = "partitionProperty";
    public static final String DEFAULT_CLUSTER_PROPERTY = "partition";

    @Context
    public GraphDatabaseAPI api;

    @Context
    public Log log;

    @Context
    public KernelTransaction transaction;

    @Procedure(value = "algo.unionFind.exp1", mode = Mode.WRITE)
    @Description("CALL algo.unionFind(label:String, relationship:String, " +
            "{property:'weight', threshold:0.42, defaultValue:1.0, write: true, partitionProperty:'partition',concurrency:4}) " +
            "YIELD nodes, setCount, loadMillis, computeMillis, writeMillis")
    public Stream<UnionFindResult> unionFind(
            @Name(value = "label", defaultValue = "") String label,
            @Name(value = "relationship", defaultValue = "") String relationship,
            @Name(value = "config", defaultValue = "{}") Map<String, Object> config) {

        ProcedureConfiguration configuration = ProcedureConfiguration.create(config)
                .overrideNodeLabelOrQuery(label)
                .overrideRelationshipTypeOrQuery(relationship);

        AllocationTracker tracker = AllocationTracker.create();
        UnionFindResult.Builder builder = UnionFindResult.builder();

        // loading
        final Graph graph;
        try (ProgressTimer timer = builder.timeLoad()) {
            graph = load(configuration, tracker);
        }

        if (graph instanceof HugeGraph) {
            HugeGraph hugeGraph = (HugeGraph) graph;

            // evaluation
            final HugeDisjointSetStruct struct;
            try (ProgressTimer timer = builder.timeEval()) {
                struct = evaluate(hugeGraph, configuration, tracker);
            }

            if (configuration.isWriteFlag()) {
                // write back
                builder.timeWrite(() ->
                        write(hugeGraph, struct, configuration));
            }

            return Stream.of(builder
                .withNodeCount(graph.nodeCount())
                .withSetCount(struct.getSetCount())
                .build());

        } else {

            // evaluation
            final DisjointSetStruct struct;
            try (ProgressTimer timer = builder.timeEval()) {
                struct = evaluate(graph, configuration);
            }

            if (configuration.isWriteFlag()) {
                // write back
                builder.timeWrite(() ->
                        write(graph, struct, configuration));
            }

            return Stream.of(builder
                .withNodeCount(graph.nodeCount())
                .withSetCount(struct.getSetCount())
                .build());
        }
    }

    @Procedure(value = "algo.unionFind.exp1.stream")
    @Description("CALL algo.unionFind.stream(label:String, relationship:String, " +
            "{property:'propertyName', threshold:0.42, defaultValue:1.0, concurrency:4}) " +
            "YIELD nodeId, setId - yields a setId to each node id")
    public Stream<DisjointSetStruct.Result> unionFindStream(
            @Name(value = "label", defaultValue = "") String label,
            @Name(value = "relationship", defaultValue = "") String relationship,
            @Name(value = "config", defaultValue = "{}") Map<String, Object> config) {

        ProcedureConfiguration configuration = ProcedureConfiguration.create(config)
                .overrideNodeLabelOrQuery(label)
                .overrideRelationshipTypeOrQuery(relationship);

        // loading
        final Graph graph = load(configuration);

        // evaluation
        return evaluate(graph, configuration)
                .resultStream(graph);
    }

    private Graph load(ProcedureConfiguration config, AllocationTracker tracker) {
        return new GraphLoader(api, Pools.DEFAULT)
                .withLog(log)
                .withOptionalLabel(config.getNodeLabelOrQuery())
                .withOptionalRelationshipType(config.getRelationshipOrQuery())
                .withOptionalRelationshipWeightsFromProperty(
                        config.getProperty(),
                        config.getPropertyDefaultValue(1.0))
                .withDirection(Direction.OUTGOING)
                .withAllocationTracker(tracker)
                .load(config.getGraphImpl());
    }

    private DisjointSetStruct evaluate(Graph graph, ProcedureConfiguration config) {

        final DisjointSetStruct struct;

        if (config.getBatchSize(-1) != -1) {
            final ParallelUnionFindQueue parallelUnionFindQueue = new ParallelUnionFindQueue(graph, Pools.DEFAULT, config.getBatchSize(), config.getConcurrency());
            if (config.containsKeys(ProcedureConstants.PROPERTY_PARAM, CONFIG_THRESHOLD)) {
                final Double threshold = config.get(CONFIG_THRESHOLD, 0.0);
                log.debug("Computing union find with threshold in parallel" + threshold);
                struct = parallelUnionFindQueue
                        .withProgressLogger(ProgressLogger.wrap(log, "CC(ParallelUnionFindQueue)"))
                        .withTerminationFlag(TerminationFlag.wrap(transaction))
                        .compute(threshold)
                        .getStruct();
            } else {
                log.debug("Computing union find without threshold in parallel");
                struct = parallelUnionFindQueue
                        .withProgressLogger(ProgressLogger.wrap(log, "CC(ParallelUnionFindQueue)"))
                        .withTerminationFlag(TerminationFlag.wrap(transaction))
                        .compute()
                        .getStruct();
            }
            parallelUnionFindQueue.release();
        } else {
            final GraphUnionFind graphUnionFind = new GraphUnionFind(graph);
            if (config.containsKeys(ProcedureConstants.PROPERTY_PARAM, CONFIG_THRESHOLD)) {
                final Double threshold = config.get(CONFIG_THRESHOLD, 0.0);
                log.debug("Computing union find with threshold " + threshold);
                struct = graphUnionFind
                        .withProgressLogger(ProgressLogger.wrap(log, "CC(SequentialUnionFind)"))
                        .withTerminationFlag(TerminationFlag.wrap(transaction))
                        .compute(threshold);
            } else {
                log.debug("Computing union find without threshold");
                struct = graphUnionFind
                        .withProgressLogger(ProgressLogger.wrap(log, "CC(SequentialUnionFind)"))
                        .withTerminationFlag(TerminationFlag.wrap(transaction))
                        .compute();
            }
            graphUnionFind.release();
            graph.release();
        }

        return struct;
    }

    private HugeDisjointSetStruct evaluate(HugeGraph graph, ProcedureConfiguration config, AllocationTracker tracker) {

        final HugeDisjointSetStruct struct;

        if (config.getBatchSize(-1) != -1) {
            final HugeParallelUnionFindQueue parallelUnionFindQueue = new HugeParallelUnionFindQueue(graph, Pools.DEFAULT, config.getBatchSize(), config.getConcurrency(), tracker);
            if (config.containsKeys(ProcedureConstants.PROPERTY_PARAM, CONFIG_THRESHOLD)) {
                final Double threshold = config.get(CONFIG_THRESHOLD, 0.0);
                log.debug("Computing union find with threshold in parallel" + threshold);
                struct = parallelUnionFindQueue
                        .withProgressLogger(ProgressLogger.wrap(log, "CC(ParallelUnionFindQueue)"))
                        .withTerminationFlag(TerminationFlag.wrap(transaction))
                        .compute(threshold)
                        .getStruct();
            } else {
                log.debug("Computing union find without threshold in parallel");
                struct = parallelUnionFindQueue
                        .withProgressLogger(ProgressLogger.wrap(log, "CC(ParallelUnionFindQueue)"))
                        .withTerminationFlag(TerminationFlag.wrap(transaction))
                        .compute()
                        .getStruct();
            }
            parallelUnionFindQueue.release();
        } else {
            final HugeGraphUnionFind graphUnionFind = new HugeGraphUnionFind(graph, tracker);
            if (config.containsKeys(ProcedureConstants.PROPERTY_PARAM, CONFIG_THRESHOLD)) {
                final Double threshold = config.get(CONFIG_THRESHOLD, 0.0);
                log.debug("Computing union find with threshold " + threshold);
                struct = graphUnionFind
                        .withProgressLogger(ProgressLogger.wrap(log, "CC(SequentialUnionFind)"))
                        .withTerminationFlag(TerminationFlag.wrap(transaction))
                        .compute(threshold);
            } else {
                log.debug("Computing union find without threshold");
                struct = graphUnionFind
                        .withProgressLogger(ProgressLogger.wrap(log, "CC(SequentialUnionFind)"))
                        .withTerminationFlag(TerminationFlag.wrap(transaction))
                        .compute();
            }
            graphUnionFind.release();
            graph.release();
        }

        return struct;
    }

    private void write(Graph graph, DisjointSetStruct struct, ProcedureConfiguration configuration) {
        log.debug("Writing results");
        Exporter.of(api, graph)
                .withLog(log)
                .parallel(Pools.DEFAULT, configuration.getConcurrency(), TerminationFlag.wrap(transaction))
                .build()
                .write(
                        configuration.get(CONFIG_CLUSTER_PROPERTY, DEFAULT_CLUSTER_PROPERTY),
                        struct,
                        DisjointSetStructTranslator.INSTANCE
                );
    }

    private void write(HugeGraph graph, HugeDisjointSetStruct struct, ProcedureConfiguration configuration) {
        log.debug("Writing results");
        Exporter.of(api, graph)
                .withLog(log)
                .parallel(Pools.DEFAULT, configuration.getConcurrency(), TerminationFlag.wrap(transaction))
                .build()
                .write(
                        configuration.get(CONFIG_CLUSTER_PROPERTY, DEFAULT_CLUSTER_PROPERTY),
                        struct,
                        HugeDisjointSetStructTranslator.INSTANCE
                );
    }
}
