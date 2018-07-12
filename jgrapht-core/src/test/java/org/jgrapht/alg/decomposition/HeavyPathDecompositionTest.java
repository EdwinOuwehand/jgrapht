/*
 * (C) Copyright 2018-2018, by Alexandru Valeanu and Contributors.
 *
 * JGraphT : a free Java graph-theory library
 *
 * This program and the accompanying materials are dual-licensed under
 * either
 *
 * (a) the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation, or (at your option) any
 * later version.
 *
 * or (per the licensee's choosing)
 *
 * (b) the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation.
 */
package org.jgrapht.alg.decomposition;

import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.Graphs;
import org.jgrapht.SlowTests;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.generate.BarabasiAlbertForestGenerator;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;
import org.jgrapht.traverse.BreadthFirstIterator;
import org.jgrapht.util.SupplierUtil;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Tests for {@link HeavyPathDecomposition}
 *
 * @author Alexandru Valeanu
 */
public class HeavyPathDecompositionTest {

    public static int log2(int n) // returns 0 for n=0
    {
        int log = 0;
        if( ( n & 0xffff0000 ) != 0 ) { n >>>= 16; log = 16; }
        if( n >= 256 ) { n >>>= 8; log += 8; }
        if( n >= 16  ) { n >>>= 4; log += 4; }
        if( n >= 4   ) { n >>>= 2; log += 2; }
        return log + ( n >>> 1 );
    }

    /*
        Count the maximum number of distinct paths on any root-to-leaf path
     */
    public static <V, E> int countMaxPath(Set<V> vertexSet, HeavyPathDecomposition<V, E> decomposition){
        Set<GraphPath<V, E>> paths = decomposition.getPathDecomposition().getPaths();
        Map<V, Integer> whichPath = new HashMap<>();

        int i = 0;
        for (GraphPath<V, E> path: paths) {
            List<V> vertexList = path.getVertexList();

            for (int j = 0; j < vertexList.size(); j++) {
                whichPath.put(vertexList.get(j), i);
            }

            i++;
        }

        int maxim = 0;
        HeavyPathDecomposition<V, E>.InternalState state = decomposition.getInternalState();

        for (V v: vertexSet){
            if (whichPath.containsKey(v)){
                int lastPath = -1;
                int cnt = 0;

                while (v != null){
                    if (lastPath != whichPath.get(v)){
                        lastPath = whichPath.get(v);
                        cnt++;
                    }

                    v = state.getParent(v);
                }

                maxim = Math.max(maxim, cnt);
            }
        }

        return maxim;
    }

    public static <V, E> boolean isValidDecomposition(Graph<V, E> graph, Set<V> roots,
                                                      HeavyPathDecomposition<V, E> decomposition){
        Set<E> heavyEdges = decomposition.getHeavyEdges();
        Set<E> lightEdges = decomposition.getLightEdges();

        Set<E> allEdges = new HashSet<>(heavyEdges);
        allEdges.addAll(lightEdges);

        // Check that heavyEdges + lightEdges = allEdges

        if (!allEdges.equals(graph.edgeSet()))
            return false;

        Set<GraphPath<V, E>> paths = decomposition.getPathDecomposition().getPaths();
        Map<V, Integer> whichPath = new HashMap<>();
        Set<E> edgesInPaths = new HashSet<>();

        int i = 0;
        for (GraphPath<V, E> path: paths) {
            List<V> vertexList = path.getVertexList();

            for (int j = 0; j < vertexList.size(); j++) {
                // Check if a vertex appear more than once in the decomposition
                if (whichPath.containsKey(vertexList.get(j)))
                    return false;

                whichPath.put(vertexList.get(j), i);

                // Check if the path is actually a valid path in the graph
                if (j > 0){
                    if (!graph.containsEdge(vertexList.get(j - 1), vertexList.get(j)))
                        return false;

                    E edge = graph.getEdge(vertexList.get(j - 1), vertexList.get(j));

                    if (!heavyEdges.contains(edge))
                        return false;

                    edgesInPaths.add(edge);
                }
            }

            i++;
        }

        for (E edge: graph.edgeSet()){
            if (edgesInPaths.contains(edge)){
                // edge must be a heavy edge

                if (!heavyEdges.contains(edge))
                    return false;
            }
            else{
                // edge must be a light edge

                if (!lightEdges.contains(edge)){
                    return false;
                }
            }
        }

        ConnectivityInspector<V, E> connectivityInspector = new ConnectivityInspector<>(graph);

        // Check if every reachable vertex from a root is in a path
        for (V root: roots){
            for (V v: connectivityInspector.connectedSetOf(root))
                if (!whichPath.containsKey(v)){
                    return false;
                }
        }

        for (V root: roots){
            BreadthFirstIterator<V, E> bfs = new BreadthFirstIterator<>(graph, root);

            List<V> postOrder = new ArrayList<>();

            while (bfs.hasNext()){
                V v = bfs.next();
                postOrder.add(v);
            }

            Collections.reverse(postOrder);

            Map<V, Integer> sizeSubtree = new HashMap<>(graph.vertexSet().size());
            for (V v: postOrder){
                sizeSubtree.put(v, 1);
                int maxSizeSubtree = -1;

                for (E edge: graph.edgesOf(v)){
                    V u = Graphs.getOppositeVertex(graph, edge, v);

                    if (!u.equals(bfs.getParent(v))){
                        int sizeU = sizeSubtree.get(u);
                        maxSizeSubtree = Math.max(maxSizeSubtree, sizeU);

                        sizeSubtree.put(v, sizeSubtree.get(v) + sizeU);
                    }
                }

                final int totalSize = sizeSubtree.get(v) - 1;

                for (E edge: graph.edgesOf(v)){
                    if (lightEdges.contains(edge)){
                        V u = Graphs.getOppositeVertex(graph, edge, v);

                        if (!u.equals(bfs.getParent(v)) && 2 * sizeSubtree.get(u) > totalSize) {
                            return false;
                        }
                    }
                    else{ // edge is heavy
                        V u = Graphs.getOppositeVertex(graph, edge, v);

                        if (!u.equals(bfs.getParent(v)) && sizeSubtree.get(u) < maxSizeSubtree) {
                            return false;
                        }
                    }
                }
            }
        }


        return countMaxPath(graph.vertexSet(), decomposition) <= log2(graph.vertexSet().size());
    }

    @Test(expected = NullPointerException.class)
    public void testNullGraph(){
        HeavyPathDecomposition<Integer, DefaultEdge> heavyPathDecomposition =
                new HeavyPathDecomposition<>(null, 1);
    }

    @Test(expected = NullPointerException.class)
    public void testNullRoot(){
        Graph<String, DefaultEdge> graph = new SimpleGraph<>(DefaultEdge.class);
        String s = null;

        HeavyPathDecomposition<String, DefaultEdge> heavyPathDecomposition =
                new HeavyPathDecomposition<>(graph, s);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRootNotInTree(){
        Graph<String, DefaultEdge> graph = new SimpleGraph<>(DefaultEdge.class);
        graph.addVertex("a");

        HeavyPathDecomposition<String, DefaultEdge> heavyPathDecomposition =
                new HeavyPathDecomposition<>(graph, "b");
    }

    @Test
    public void testOneVertex(){
        Graph<String, DefaultEdge> graph = new SimpleGraph<>(DefaultEdge.class);
        graph.addVertex("a");

        HeavyPathDecomposition<String, DefaultEdge> heavyPathDecomposition =
                new HeavyPathDecomposition<>(graph, "a");

        Assert.assertEquals(1, heavyPathDecomposition.getPathDecomposition().numberOfPaths());
    }

    @Test
    public void testLineGraph(){
        Graph<Integer, DefaultEdge> graph = new SimpleGraph<>(DefaultEdge.class);

        for (int i = 1; i <= 11; i++)
            graph.addVertex(i);

        for (int i = 1; i < 11; i++)
            graph.addEdge(i, i + 1);

        HeavyPathDecomposition<Integer, DefaultEdge> heavyPathDecomposition = new HeavyPathDecomposition<>(graph, 1);

        Assert.assertTrue(isValidDecomposition(graph, Collections.singleton(1), heavyPathDecomposition));
        Assert.assertEquals(1, heavyPathDecomposition.getPathDecomposition().numberOfPaths());
    }

    @Test
    public void testLineGraph2(){
        Graph<Integer, DefaultEdge> graph = new SimpleGraph<>(DefaultEdge.class);

        for (int i = 1; i <= 11; i++)
            graph.addVertex(i);

        for (int i = 1; i < 11; i++)
            graph.addEdge(i, i + 1);

        HeavyPathDecomposition<Integer, DefaultEdge> heavyPathDecomposition = new HeavyPathDecomposition<>(graph, 5);

        Assert.assertTrue(isValidDecomposition(graph, Collections.singleton(5), heavyPathDecomposition));
        Assert.assertEquals(2, heavyPathDecomposition.getPathDecomposition().numberOfPaths());
    }

    @Test
    public void testSmallTree(){
        Graph<Integer, DefaultEdge> graph = new SimpleGraph<>(DefaultEdge.class);

        for (int i = 1; i <= 11; i++)
            graph.addVertex(i);

        graph.addEdge(1, 2);
        graph.addEdge(2, 4);
        graph.addEdge(2, 5);
        graph.addEdge(2, 6);
        graph.addEdge(4, 7);
        graph.addEdge(4, 8);
        graph.addEdge(6, 9);
        graph.addEdge(1, 3);
        graph.addEdge(3, 10);
        graph.addEdge(3, 11);

        HeavyPathDecomposition<Integer, DefaultEdge> heavyPathDecomposition = new HeavyPathDecomposition<>(graph, 1);

        Assert.assertTrue(isValidDecomposition(graph, Collections.singleton(1), heavyPathDecomposition));
    }

    @Test
    public void testDisconnectedSmallGraph(){
        Graph<Integer, DefaultEdge> graph = new SimpleGraph<>(DefaultEdge.class);

        graph.addVertex(1);
        graph.addVertex(2);
        graph.addEdge(1, 2);
        graph.addVertex(3);
        graph.addVertex(4);
        graph.addEdge(3, 4);

        HeavyPathDecomposition<Integer, DefaultEdge> heavyPathDecomposition = new HeavyPathDecomposition<>(graph, 1);

        Assert.assertTrue(isValidDecomposition(graph, Collections.singleton(1), heavyPathDecomposition));
        Assert.assertEquals(1, heavyPathDecomposition.getPathDecomposition().numberOfPaths());
    }

    @Test
    public void testRandomTrees(){
        final int NUM_TESTS = 100;
        Random random = new Random(0x2882);

        for (int test = 0; test < NUM_TESTS; test++) {
            Graph<Integer, DefaultEdge> graph = new SimpleGraph<>(
                    SupplierUtil.createIntegerSupplier(0), SupplierUtil.DEFAULT_EDGE_SUPPLIER, false);

            BarabasiAlbertForestGenerator<Integer, DefaultEdge> generator =
                    new BarabasiAlbertForestGenerator<>(1,
                            1024 + random.nextInt(1 << 12), random);

            generator.generateGraph(graph, null);

            Set<Integer> roots = Collections.singleton(graph.vertexSet().iterator().next());

            HeavyPathDecomposition<Integer, DefaultEdge> heavyPathDecomposition =
                    new HeavyPathDecomposition<>(graph, roots);

            Assert.assertTrue(isValidDecomposition(graph, roots, heavyPathDecomposition));
        }
    }

    @Test
    public void testRandomForests(){
        final int NUM_TESTS = 1000;
        Random random = new Random(0x1881);

        for (int test = 0; test < NUM_TESTS; test++) {
            Graph<Integer, DefaultEdge> graph = new SimpleGraph<>(
                    SupplierUtil.createIntegerSupplier(0), SupplierUtil.DEFAULT_EDGE_SUPPLIER, false);

            BarabasiAlbertForestGenerator<Integer, DefaultEdge> generator =
                    new BarabasiAlbertForestGenerator<>(1 + random.nextInt(20),
                            50 + random.nextInt(1 << 11), random);

            generator.generateGraph(graph, null);

            ConnectivityInspector<Integer, DefaultEdge> connectivityInspector = new ConnectivityInspector<>(graph);
            List<Set<Integer>> connectedComponents = connectivityInspector.connectedSets();
            Set<Integer> roots = connectedComponents.stream().map(component -> component.iterator().next()).collect(Collectors.toSet());

            HeavyPathDecomposition<Integer, DefaultEdge> heavyPathDecomposition =
                    new HeavyPathDecomposition<>(graph, roots);

            Assert.assertTrue(isValidDecomposition(graph, roots, heavyPathDecomposition));
        }
    }

    @Category(SlowTests.class)
    @Test
    public void testHugeTree(){
        Random random = new Random(0x118811);

        Graph<Integer, DefaultEdge> graph = new SimpleGraph<>(
                SupplierUtil.createIntegerSupplier(0), SupplierUtil.DEFAULT_EDGE_SUPPLIER, false);

        BarabasiAlbertForestGenerator<Integer, DefaultEdge> generator =
                new BarabasiAlbertForestGenerator<>(1,
                        1 << 19, random);

        generator.generateGraph(graph, null);

        Set<Integer> roots = Collections.singleton(graph.vertexSet().iterator().next());

        HeavyPathDecomposition<Integer, DefaultEdge> heavyPathDecomposition =
                new HeavyPathDecomposition<>(graph, roots);

        Assert.assertTrue(isValidDecomposition(graph, roots, heavyPathDecomposition));
    }
}