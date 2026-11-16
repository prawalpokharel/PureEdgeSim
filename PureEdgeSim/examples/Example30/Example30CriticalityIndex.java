package examples;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency criticality of the subtasks of a service graph.
 * <p>
 * The score of a subtask is {@code alpha * C + beta * H + gamma * R}, the
 * Centrality-Entropy Index (Pokharel, FTC 2026 and IEMCON 2026):
 * <ul>
 * <li>{@code C} is a composite centrality on the subtask graph, with an edge
 * from each subtask to every subtask it depends on: 0.35 PageRank, 0.30
 * betweenness, 0.20 degree and 0.15 closeness, divided by the largest value in
 * the application. A subtask that many others wait for scores high.</li>
 * <li>{@code H} is the normalized Shannon entropy, over 10 bins, of the last
 * {@value #HISTORY} values of total time divided by latency budget observed for
 * that subtask. A subtask whose timing is unpredictable scores high.</li>
 * <li>{@code R} is a governance term set by the application type: 1 for hard
 * real-time, 0.5 for soft real-time, 0 otherwise.</li>
 * </ul>
 * The four centralities follow the definitions of networkx (PageRank with
 * uniform teleport and dangling mass, normalized directed betweenness, in plus
 * out degree over n - 1, and incoming closeness with the Wasserman and Faust
 * correction), so the scores can be checked against a Python reference.
 */
public class Example30CriticalityIndex {

	/** Default weight of the centrality term. */
	public static final double DEFAULT_ALPHA = 0.40;
	/** Default weight of the entropy term. */
	public static final double DEFAULT_BETA = 0.35;
	/** Default weight of the governance term. */
	public static final double DEFAULT_GAMMA = 0.25;
	/** Number of observations kept per subtask for the entropy term. */
	static final int HISTORY = 50;
	private static final int BINS = 10;

	private final double alpha;
	private final double beta;
	private final double gamma;
	private final Map<String, Double> centrality = new HashMap<>();
	private final Map<String, Double> reach = new HashMap<>();
	private final Map<String, Boolean> inner = new HashMap<>();
	private final Map<Integer, Double> governance = new HashMap<>();
	private final Map<String, Deque<Double>> history = new HashMap<>();

	/**
	 * Creates an index with the given weights.
	 *
	 * @param alpha weight of the centrality term
	 * @param beta  weight of the entropy term
	 * @param gamma weight of the governance term
	 */
	public Example30CriticalityIndex(double alpha, double beta, double gamma) {
		this.alpha = alpha;
		this.beta = beta;
		this.gamma = gamma;
	}

	/**
	 * Registers the subtask graph and the type of one application.
	 *
	 * @param applicationId index of the application in the applications file
	 * @param type          application type, used by the governance term
	 * @param dependencies  for each subtask, the subtasks it depends on, in file
	 *                      order
	 */
	public void addApplication(int applicationId, String type, Map<String, List<String>> dependencies) {
		governance.put(applicationId, governance(type));
		Map<String, Double> c = compositeCentrality(dependencies);
		Map<String, Double> r = downstreamReach(dependencies);
		for (String subtask : dependencies.keySet()) {
			String key = key(applicationId, subtask);
			centrality.put(key, c.get(subtask));
			reach.put(key, r.get(subtask));
			inner.put(key, r.get(subtask) > 0);
		}
	}

	/**
	 * Returns the criticality score of a subtask, in [0, 1] when the weights sum to
	 * one. A task that is not part of a registered graph has no centrality.
	 *
	 * @param applicationId the application of the task
	 * @param subtask       the subtask name, or {@code null}
	 * @return the score
	 */
	public double score(int applicationId, String subtask) {
		String key = key(applicationId, subtask);
		double c = centrality.getOrDefault(key, 0.0);
		double h = entropy(history.getOrDefault(key, new ArrayDeque<Double>()));
		double r = governance.getOrDefault(applicationId, 0.0);
		return alpha * c + beta * h + gamma * r;
	}

	/**
	 * Returns the share of the other subtasks of the application that transitively
	 * depend on this one.
	 *
	 * @param applicationId the application of the task
	 * @param subtask       the subtask name, or {@code null}
	 * @return the downstream reach in [0, 1]
	 */
	public double reach(int applicationId, String subtask) {
		return reach.getOrDefault(key(applicationId, subtask), 0.0);
	}

	/**
	 * Returns whether at least one other subtask depends on this one.
	 *
	 * @param applicationId the application of the task
	 * @param subtask       the subtask name, or {@code null}
	 * @return {@code true} for a subtask that is not a leaf of the graph
	 */
	public boolean hasDependents(int applicationId, String subtask) {
		return inner.getOrDefault(key(applicationId, subtask), false);
	}

	/**
	 * Records the total time of a returned subtask as a share of its latency
	 * budget, for the entropy term.
	 *
	 * @param applicationId the application of the task
	 * @param subtask       the subtask name, or {@code null}
	 * @param ratio         total time divided by latency budget
	 */
	public void observe(int applicationId, String subtask, double ratio) {
		Deque<Double> values = history.computeIfAbsent(key(applicationId, subtask), k -> new ArrayDeque<Double>());
		values.addLast(Math.max(0, Math.min(1, ratio)));
		if (values.size() > HISTORY)
			values.removeFirst();
	}

	/**
	 * Maps an application type to the governance term.
	 *
	 * @param type the application type
	 * @return 1 for hard real-time, 0.5 for soft real-time, 0 otherwise
	 */
	public static double governance(String type) {
		if ("Hard Real-time".equals(type))
			return 1;
		if ("Soft Real-time".equals(type))
			return 0.5;
		return 0;
	}

	/**
	 * Normalized Shannon entropy of values in [0, 1], over 10 equal bins.
	 *
	 * @param values the observations
	 * @return the entropy divided by log2(10), 0 for no observations
	 */
	public static double entropy(Collection<Double> values) {
		if (values.isEmpty())
			return 0;
		int[] counts = new int[BINS];
		for (double v : values)
			counts[Math.min(BINS - 1, (int) Math.floor(v * BINS))]++;
		double h = 0;
		for (int count : counts) {
			if (count == 0)
				continue;
			double p = (double) count / values.size();
			h -= p * Math.log(p) / Math.log(2);
		}
		return Math.min(1, h / (Math.log(BINS) / Math.log(2)));
	}

	/**
	 * Composite centrality of every subtask, divided by the largest value.
	 *
	 * @param dependencies for each subtask, the subtasks it depends on
	 * @return the centrality of each subtask in [0, 1]
	 */
	public static Map<String, Double> compositeCentrality(Map<String, List<String>> dependencies) {
		List<String> names = new ArrayList<>(dependencies.keySet());
		int n = names.size();
		Map<String, Double> result = new LinkedHashMap<>();
		if (n == 0)
			return result;
		boolean[][] edge = adjacency(names, dependencies);
		double[] pr = pageRank(edge);
		double[] bw = betweenness(edge);
		double[] cl = closeness(edge);
		double max = 0;
		double[] composite = new double[n];
		for (int i = 0; i < n; i++) {
			int degree = 0;
			for (int j = 0; j < n; j++)
				degree += (edge[i][j] ? 1 : 0) + (edge[j][i] ? 1 : 0);
			double dg = n > 1 ? degree / (double) (n - 1) : 0;
			composite[i] = 0.35 * pr[i] + 0.30 * bw[i] + 0.20 * dg + 0.15 * cl[i];
			max = Math.max(max, composite[i]);
		}
		for (int i = 0; i < n; i++)
			result.put(names.get(i), max > 0 ? composite[i] / max : 0);
		return result;
	}

	/**
	 * Share of the other subtasks that transitively depend on each subtask.
	 *
	 * @param dependencies for each subtask, the subtasks it depends on
	 * @return the downstream reach of each subtask in [0, 1]
	 */
	public static Map<String, Double> downstreamReach(Map<String, List<String>> dependencies) {
		List<String> names = new ArrayList<>(dependencies.keySet());
		int n = names.size();
		boolean[][] edge = adjacency(names, dependencies);
		Map<String, Double> result = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			// Subtasks that reach i along dependency edges are the ones waiting for it.
			int[] dist = distancesTo(edge, i);
			int count = 0;
			for (int j = 0; j < n; j++)
				count += (j != i && dist[j] >= 0) ? 1 : 0;
			result.put(names.get(i), n > 1 ? count / (double) (n - 1) : 0);
		}
		return result;
	}

	private static boolean[][] adjacency(List<String> names, Map<String, List<String>> dependencies) {
		int n = names.size();
		boolean[][] edge = new boolean[n][n];
		for (int i = 0; i < n; i++)
			for (String dependency : dependencies.get(names.get(i))) {
				int j = names.indexOf(dependency);
				if (j < 0)
					throw new IllegalArgumentException("Unknown dependency '" + dependency + "' of " + names.get(i));
				edge[i][j] = true;
			}
		return edge;
	}

	/** PageRank with damping 0.85, uniform teleport and dangling mass, as networkx. */
	static double[] pageRank(boolean[][] edge) {
		int n = edge.length;
		int[] out = new int[n];
		for (int i = 0; i < n; i++)
			for (int j = 0; j < n; j++)
				out[i] += edge[i][j] ? 1 : 0;
		double[] x = new double[n];
		java.util.Arrays.fill(x, 1.0 / n);
		for (int iteration = 0; iteration < 100; iteration++) {
			double dangling = 0;
			for (int i = 0; i < n; i++)
				if (out[i] == 0)
					dangling += x[i];
			double[] next = new double[n];
			for (int i = 0; i < n; i++)
				for (int j = 0; j < n; j++)
					if (edge[i][j])
						next[j] += 0.85 * x[i] / out[i];
			double error = 0;
			for (int j = 0; j < n; j++) {
				next[j] += 0.85 * dangling / n + 0.15 / n;
				error += Math.abs(next[j] - x[j]);
			}
			x = next;
			if (error < n * 1e-6)
				break;
		}
		return x;
	}

	/** Brandes betweenness for an unweighted directed graph, normalized by (n-1)(n-2). */
	static double[] betweenness(boolean[][] edge) {
		int n = edge.length;
		double[] cb = new double[n];
		for (int s = 0; s < n; s++) {
			Deque<Integer> stack = new ArrayDeque<>();
			List<List<Integer>> pred = new ArrayList<>();
			for (int i = 0; i < n; i++)
				pred.add(new ArrayList<Integer>());
			double[] sigma = new double[n];
			int[] dist = new int[n];
			java.util.Arrays.fill(dist, -1);
			sigma[s] = 1;
			dist[s] = 0;
			Deque<Integer> queue = new ArrayDeque<>();
			queue.add(s);
			while (!queue.isEmpty()) {
				int v = queue.poll();
				stack.push(v);
				for (int w = 0; w < n; w++) {
					if (!edge[v][w])
						continue;
					if (dist[w] < 0) {
						dist[w] = dist[v] + 1;
						queue.add(w);
					}
					if (dist[w] == dist[v] + 1) {
						sigma[w] += sigma[v];
						pred.get(w).add(v);
					}
				}
			}
			double[] delta = new double[n];
			while (!stack.isEmpty()) {
				int w = stack.pop();
				for (int v : pred.get(w))
					delta[v] += sigma[v] / sigma[w] * (1 + delta[w]);
				if (w != s)
					cb[w] += delta[w];
			}
		}
		if (n > 2)
			for (int i = 0; i < n; i++)
				cb[i] /= (double) (n - 1) * (n - 2);
		return cb;
	}

	/** Incoming closeness with the Wasserman and Faust correction, as networkx. */
	static double[] closeness(boolean[][] edge) {
		int n = edge.length;
		double[] c = new double[n];
		for (int u = 0; u < n; u++) {
			int[] dist = distancesTo(edge, u);
			int reachable = 0;
			int total = 0;
			for (int d : dist)
				if (d >= 0) {
					reachable++;
					total += d;
				}
			if (total > 0 && n > 1)
				c[u] = (reachable - 1.0) / total * ((reachable - 1.0) / (n - 1));
		}
		return c;
	}

	/** Length of the shortest path from every node to {@code target}, -1 if none. */
	private static int[] distancesTo(boolean[][] edge, int target) {
		int n = edge.length;
		int[] dist = new int[n];
		java.util.Arrays.fill(dist, -1);
		dist[target] = 0;
		Deque<Integer> queue = new ArrayDeque<>();
		queue.add(target);
		while (!queue.isEmpty()) {
			int v = queue.poll();
			for (int u = 0; u < n; u++)
				if (edge[u][v] && dist[u] < 0) {
					dist[u] = dist[v] + 1;
					queue.add(u);
				}
		}
		return dist;
	}

	private static String key(int applicationId, String subtask) {
		return applicationId + "#" + (subtask == null ? "" : subtask);
	}
}
