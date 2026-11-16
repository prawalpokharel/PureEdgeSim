package examples;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode.LinkOrientation;
import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;
import com.mechalikh.pureedgesim.taskgenerator.Application;
import com.mechalikh.pureedgesim.taskgenerator.Subtask;
import com.mechalikh.pureedgesim.taskgenerator.Task;
import com.mechalikh.pureedgesim.taskorchestrator.DefaultOrchestrator;

/**
 * Places the subtasks of service graphs by how much of the graph depends on
 * them.
 * <p>
 * When a subtask fails, PureEdgeSim fails every subtask that depends on it, so
 * a failure early in a graph costs the whole request. This orchestrator weighs
 * the estimated delay of each candidate against the risk that the node fails
 * before the subtask finishes, scaled by how critical the subtask is:
 * {@code cost = delay / latency budget + KAPPA * criticality * risk}. Cloud
 * virtual machines and edge data centers carry no risk. An edge device carries
 * {@code 0.25}, plus up to {@code 0.5} when it runs on a battery (more as the
 * battery empties), plus {@code 0.5} when it moves, capped at 1.
 * <p>
 * The algorithm name selects how criticality is measured:
 * <ul>
 * <li>{@code CRITICALITY}: the Centrality-Entropy Index of
 * {@link Example30CriticalityIndex}, with its default weights.</li>
 * <li>{@code CRITICALITY_C}, {@code CRITICALITY_H}, {@code CRITICALITY_G}: one
 * term of the index alone (centrality, entropy, governance).</li>
 * <li>{@code DOWNSTREAM_REACH}: the share of the graph that transitively waits
 * for the subtask.</li>
 * <li>{@code RELIABLE_INNER}: a subtask that any other subtask waits for may
 * only go to a node without risk, when one is allowed; otherwise the lowest
 * delay wins.</li>
 * <li>{@code LATENCY_GREEDY}: the lowest delay fraction, ignoring risk.</li>
 * <li>{@code TRADE_OFF} and {@code ROUND_ROBIN}: the default orchestrator.</li>
 * </ul>
 */
public class Example30CriticalityOrchestrator extends DefaultOrchestrator {

	/** Weight of the risk term against the delay fraction, fixed before any run. */
	static final double KAPPA = 1.0;

	/** The algorithm names this orchestrator accepts. */
	static final List<String> ALGORITHMS = Arrays.asList("TRADE_OFF", "ROUND_ROBIN", "LATENCY_GREEDY",
			"RELIABLE_INNER", "DOWNSTREAM_REACH", "CRITICALITY", "CRITICALITY_C", "CRITICALITY_H", "CRITICALITY_G");

	private final Example30CriticalityIndex index;
	private final int[] pendingPerNode;
	private final Map<Integer, Integer> decisions = new HashMap<>();

	/**
	 * Creates the orchestrator and reads the subtask graphs of the applications.
	 *
	 * @param simulationManager the simulation manager
	 */
	public Example30CriticalityOrchestrator(SimulationManager simulationManager) {
		super(simulationManager);
		if (!ALGORITHMS.contains(algorithmName))
			throw new IllegalArgumentException(getClass().getSimpleName() + " - Unknown orchestration algorithm '"
					+ algorithmName + "', expected one of " + ALGORITHMS);
		index = indexFor(algorithmName);
		if (SimulationParameters.applicationList != null)
			registerApplications(index, SimulationParameters.applicationList);
		pendingPerNode = new int[nodeList.size()];
	}

	/**
	 * Returns an index weighted for the given algorithm name.
	 *
	 * @param algorithm the orchestration algorithm
	 * @return the criticality index it uses
	 */
	static Example30CriticalityIndex indexFor(String algorithm) {
		if ("CRITICALITY_C".equals(algorithm))
			return new Example30CriticalityIndex(1, 0, 0);
		if ("CRITICALITY_H".equals(algorithm))
			return new Example30CriticalityIndex(0, 1, 0);
		if ("CRITICALITY_G".equals(algorithm))
			return new Example30CriticalityIndex(0, 0, 1);
		return new Example30CriticalityIndex(Example30CriticalityIndex.DEFAULT_ALPHA,
				Example30CriticalityIndex.DEFAULT_BETA, Example30CriticalityIndex.DEFAULT_GAMMA);
	}

	/**
	 * Registers the subtask graph of every application that declares one.
	 *
	 * @param index        the index to fill
	 * @param applications the applications of the scenario
	 */
	static void registerApplications(Example30CriticalityIndex index, List<Application> applications) {
		for (int id = 0; id < applications.size(); id++) {
			Application application = applications.get(id);
			if (!application.hasSubtasks())
				continue;
			Map<String, List<String>> dependencies = new LinkedHashMap<>();
			for (Subtask subtask : application.getSubtasks())
				dependencies.put(subtask.getName(), subtask.getDependencies());
			index.addApplication(id, application.getType(), dependencies);
		}
	}

	/**
	 * Places the task, then clears the cached placement of its device so the next
	 * subtask is placed on its own, as in Example 9. Every algorithm, the default
	 * ones included, therefore decides once per subtask.
	 *
	 * @param task the task to place
	 */
	@Override
	public void orchestrate(Task task) {
		super.orchestrate(task);
		task.getEdgeDevice().setApplicationPlaced(false);
	}

	@Override
	protected int findComputingNode(String[] architecture, Task task) {
		if ("TRADE_OFF".equals(algorithmName) || "ROUND_ROBIN".equals(algorithmName))
			return super.findComputingNode(architecture, task);
		double criticality = criticality(task);
		boolean reliableOnly = "RELIABLE_INNER".equals(algorithmName)
				&& index.hasDependents(task.getApplicationID(), task.getSubtaskName()) && anyReliable(task, architecture);
		int best = -1;
		double bestCost = Double.MAX_VALUE;
		for (int i = 0; i < nodeList.size(); i++) {
			ComputingNode node = nodeList.get(i);
			if (!offloadingIsPossible(task, node, architecture))
				continue;
			double risk = risk(node);
			if (reliableOnly && risk > 0)
				continue;
			double cost = estimatedDelay(task, node, i) / Math.max(task.getMaxLatency(), 1e-9) + KAPPA * criticality * risk;
			if (cost < bestCost) {
				bestCost = cost;
				best = i;
			}
		}
		if (best >= 0) {
			pendingPerNode[best]++;
			decisions.put(task.getId(), best);
		}
		return best;
	}

	/**
	 * Returns the criticality the current algorithm gives to a task.
	 *
	 * @param task the task to place
	 * @return the criticality, 0 for the algorithms that ignore risk
	 */
	double criticality(Task task) {
		if (algorithmName.startsWith("CRITICALITY"))
			return index.score(task.getApplicationID(), task.getSubtaskName());
		if ("DOWNSTREAM_REACH".equals(algorithmName))
			return index.reach(task.getApplicationID(), task.getSubtaskName());
		if ("LATENCY_GREEDY".equals(algorithmName) || "RELIABLE_INNER".equals(algorithmName))
			return 0;
		throw new IllegalArgumentException(getClass().getSimpleName() + " - Unknown orchestration algorithm '"
				+ algorithmName + "', please check the simulation parameters file...");
	}

	/**
	 * Returns the risk that a node fails before a task placed on it finishes.
	 *
	 * @param node the candidate node
	 * @return 0 for cloud and edge data centers, between 0.25 and 1 for devices
	 */
	static double risk(ComputingNode node) {
		if (node.getType() != SimulationParameters.TYPES.EDGE_DEVICE)
			return 0;
		double risk = 0.25;
		if (node.getEnergyModel() != null && node.getEnergyModel().isBatteryPowered()) {
			double level = node.getEnergyModel().getBatteryLevelPercentage() / 100.0;
			risk += 0.25 + 0.25 * (1 - Math.max(0, Math.min(1, level)));
		}
		if (node.getMobilityModel() != null && node.getMobilityModel().isMobile())
			risk += 0.5;
		return Math.min(1, risk);
	}

	private boolean anyReliable(Task task, String[] architecture) {
		for (ComputingNode node : nodeList)
			if (risk(node) == 0 && offloadingIsPossible(task, node, architecture))
				return true;
		return false;
	}

	/**
	 * Estimated time to finish a task on a node: its compute time, multiplied by
	 * the tasks already sent there and not finished, plus the access latency and,
	 * for the cloud, the WAN latency. This is the estimator of Example 19.
	 */
	private double estimatedDelay(Task task, ComputingNode node, int index) {
		double capacity = node.getMipsPerCore() * node.getNumberOfCPUCores();
		double delay = task.getLength() / Math.max(1, capacity) * (1 + pendingPerNode[index]);
		if (task.getEdgeDevice() != null && task.getEdgeDevice().getCurrentLink(LinkOrientation.UP_LINK) != null)
			delay += task.getEdgeDevice().getCurrentLink(LinkOrientation.UP_LINK).getLatency();
		if (node.getType() == SimulationParameters.TYPES.CLOUD)
			delay += SimulationParameters.wanLatency;
		return delay;
	}

	/**
	 * Releases the placement of a task whose results came back, and records its
	 * total time as a share of its budget for the entropy term. PureEdgeSim also
	 * calls this for a task that failed after it was sent; such a task counts as
	 * a full budget.
	 *
	 * @param task the task
	 */
	@Override
	public void resultsReturned(Task task) {
		release(task);
		double ratio = task.getStatus() == Task.Status.FAILED ? 1
				: task.getTotalDelay() / Math.max(task.getMaxLatency(), 1e-9);
		index.observe(task.getApplicationID(), task.getSubtaskName(), ratio);
	}

	/**
	 * Forgets the placement of a task that ended without returning results, so the
	 * delay estimate does not count it any longer.
	 *
	 * @param task the failed task
	 */
	public void taskEnded(Task task) {
		release(task);
	}

	/** Returns the criticality index, for tests. */
	Example30CriticalityIndex index() {
		return index;
	}

	/** Returns the tasks sent to a node and not finished yet, for tests. */
	int pending(int node) {
		return pendingPerNode[node];
	}

	private void release(Task task) {
		Integer node = decisions.remove(task.getId());
		if (node != null && pendingPerNode[node] > 0)
			pendingPerNode[node]--;
	}
}
