package examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.mechalikh.pureedgesim.AbstractTest;
import com.mechalikh.pureedgesim.SimLogCapture;
import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode.LinkOrientation;
import com.mechalikh.pureedgesim.datacentersmanager.ComputingNodesGenerator;
import com.mechalikh.pureedgesim.datacentersmanager.DataCentersManager;
import com.mechalikh.pureedgesim.energy.EnergyModelComputingNode;
import com.mechalikh.pureedgesim.locationmanager.MobilityModel;
import com.mechalikh.pureedgesim.network.NetworkLink;
import com.mechalikh.pureedgesim.scenariomanager.Scenario;
import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;
import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters.TYPES;
import com.mechalikh.pureedgesim.simulationengine.PureEdgeSim;
import com.mechalikh.pureedgesim.simulationmanager.DefaultSimulationManager;
import com.mechalikh.pureedgesim.simulationmanager.SimLog;
import com.mechalikh.pureedgesim.taskgenerator.Task;

class Example30CriticalityOrchestratorTest extends AbstractTest {

	private static final String[] ALL = { "Cloud", "Edge", "Mist" };

	private DefaultSimulationManager manager;
	private ComputingNodesGenerator generator;

	/** The video analytics graph of Example 9, as subtask to dependencies. */
	static Map<String, List<String>> video() {
		Map<String, List<String>> graph = new LinkedHashMap<>();
		graph.put("capture", Collections.<String>emptyList());
		graph.put("preprocess", Arrays.asList("capture"));
		graph.put("detect", Arrays.asList("preprocess"));
		graph.put("classify", Arrays.asList("detect"));
		graph.put("track", Arrays.asList("detect"));
		graph.put("aggregate", Arrays.asList("classify", "track"));
		graph.put("notify", Arrays.asList("aggregate"));
		return graph;
	}

	/** One ingest feeding three analyses that a fusion step joins. */
	static Map<String, List<String>> health() {
		Map<String, List<String>> graph = new LinkedHashMap<>();
		graph.put("ingest", Collections.<String>emptyList());
		graph.put("ecg", Arrays.asList("ingest"));
		graph.put("spo2", Arrays.asList("ingest"));
		graph.put("motion", Arrays.asList("ingest"));
		graph.put("fuse", Arrays.asList("ecg", "spo2", "motion"));
		graph.put("alert", Arrays.asList("fuse"));
		return graph;
	}

	@BeforeEach
	void setUp() {
		SimulationParameters.orchestrationArchitectures = new String[] { "ALL" };
		SimulationParameters.orchestrationAlgorithms = new String[] { "CRITICALITY" };
		SimulationParameters.edgeDevicesRange = 100;
		SimulationParameters.enableOrchestrators = false;
		SimulationParameters.applicationList = null;

		Scenario scenario = new Scenario(0, 0, 0);
		manager = new DefaultSimulationManager(new SimLog("test", false), new PureEdgeSim(), 0, 0, scenario);
		DataCentersManager dataCentersManager = mock(DataCentersManager.class);
		generator = mock(ComputingNodesGenerator.class);
		when(dataCentersManager.getComputingNodesGenerator()).thenReturn(generator);
		manager.setDataCentersManager(dataCentersManager);
	}

	private ComputingNode node(TYPES type, double mips, boolean battery, double level, boolean mobile) {
		ComputingNode node = mock(ComputingNode.class);
		when(node.getType()).thenReturn(type);
		when(node.getMipsPerCore()).thenReturn(mips);
		when(node.getNumberOfCPUCores()).thenReturn(1.0);
		EnergyModelComputingNode energy = mock(EnergyModelComputingNode.class);
		when(energy.isBatteryPowered()).thenReturn(battery);
		when(energy.getBatteryLevelPercentage()).thenReturn(level);
		when(node.getEnergyModel()).thenReturn(energy);
		MobilityModel mobility = mock(MobilityModel.class);
		when(mobility.isMobile()).thenReturn(mobile);
		when(mobility.distanceTo(org.mockito.ArgumentMatchers.any())).thenReturn(1.0);
		when(node.getMobilityModel()).thenReturn(mobility);
		return node;
	}

	private Task task(int id, int application, String subtask, double length, double latency, ComputingNode edge) {
		Task task = mock(Task.class);
		when(task.getId()).thenReturn(id);
		when(task.getApplicationID()).thenReturn(application);
		when(task.getSubtaskName()).thenReturn(subtask);
		when(task.getLength()).thenReturn(length);
		when(task.getMaxLatency()).thenReturn(latency);
		ComputingNode origin = node(TYPES.EDGE_DEVICE, 1, false, 0, false);
		NetworkLink up = mock(NetworkLink.class);
		when(up.getDst()).thenReturn(edge);
		when(up.getLatency()).thenReturn(0.005);
		when(origin.getCurrentLink(LinkOrientation.UP_LINK)).thenReturn(up);
		when(task.getEdgeDevice()).thenReturn(origin);
		return task;
	}

	private Example30CriticalityOrchestrator orchestrator(String algorithm, List<ComputingNode> nodes) {
		SimulationParameters.orchestrationAlgorithms = new String[] { algorithm };
		when(generator.getAllNodesListSensorsExcluded()).thenReturn(nodes);
		Example30CriticalityOrchestrator orchestrator = new Example30CriticalityOrchestrator(manager);
		return orchestrator;
	}

	@Test
	void compositeCentralityMatchesTheNetworkxReference() {
		// Values from _shared/cei_ref.py composite_centrality (networkx 3.4.2) on the
		// same graphs, with edges from a subtask to each subtask it depends on.
		Map<String, Double> video = Example30CriticalityIndex.compositeCentrality(video());
		Map<String, Double> health = Example30CriticalityIndex.compositeCentrality(health());
		for (Map.Entry<String, Double> expected : ExpectedCentrality.VIDEO.entrySet())
			assertEquals(expected.getValue(), video.get(expected.getKey()), 1e-6, expected.getKey());
		for (Map.Entry<String, Double> expected : ExpectedCentrality.HEALTH.entrySet())
			assertEquals(expected.getValue(), health.get(expected.getKey()), 1e-6, expected.getKey());
	}

	@Test
	void downstreamReachCountsEverySubtaskThatWaits() {
		Map<String, Double> reach = Example30CriticalityIndex.downstreamReach(video());
		assertEquals(1.0, reach.get("capture"), 1e-12);
		assertEquals(4.0 / 6, reach.get("detect"), 1e-12);
		assertEquals(2.0 / 6, reach.get("classify"), 1e-12);
		assertEquals(0.0, reach.get("notify"), 1e-12);
	}

	@Test
	void entropyIsZeroForConstantTimingAndOneForTimingSpreadOverEveryBin() {
		assertEquals(0, Example30CriticalityIndex.entropy(Collections.<Double>emptyList()), 1e-12);
		assertEquals(0, Example30CriticalityIndex.entropy(Arrays.asList(0.42, 0.42, 0.42)), 1e-12);
		assertEquals(1, Example30CriticalityIndex.entropy(
				Arrays.asList(0.05, 0.15, 0.25, 0.35, 0.45, 0.55, 0.65, 0.75, 0.85, 0.95)), 1e-12);
		assertEquals(Math.log(2) / Math.log(10), Example30CriticalityIndex.entropy(Arrays.asList(0.0, 1.0)),
				1e-12);
	}

	@Test
	void governanceFollowsTheApplicationType() {
		assertEquals(1, Example30CriticalityIndex.governance("Hard Real-time"), 0);
		assertEquals(0.5, Example30CriticalityIndex.governance("Soft Real-time"), 0);
		assertEquals(0, Example30CriticalityIndex.governance("Non Real-time"), 0);
	}

	@Test
	void theScoreCombinesTheThreeTermsWithTheirWeights() {
		Example30CriticalityIndex index = new Example30CriticalityIndex(0.4, 0.35, 0.25);
		index.addApplication(0, "Hard Real-time", health());
		index.observe(0, "ingest", 0.05);
		index.observe(0, "ingest", 0.95);
		double expected = 0.4 * ExpectedCentrality.HEALTH.get("ingest") + 0.35 * Math.log(2) / Math.log(10)
				+ 0.25 * 1;
		assertEquals(expected, index.score(0, "ingest"), 1e-6);
		assertTrue(index.hasDependents(0, "ingest"));
		assertTrue(!index.hasDependents(0, "alert"));
		assertEquals(0, index.score(7, "unknown"), 1e-12);
	}

	@Test
	void riskIsZeroForInfrastructureAndGrowsWithBatteryAndMobility() {
		assertEquals(0, Example30CriticalityOrchestrator.risk(node(TYPES.CLOUD, 1, false, 0, false)), 1e-12);
		assertEquals(0, Example30CriticalityOrchestrator.risk(node(TYPES.EDGE_DATACENTER, 1, false, 0, false)),
				1e-12);
		assertEquals(0.25, Example30CriticalityOrchestrator.risk(node(TYPES.EDGE_DEVICE, 1, false, 0, false)), 1e-12);
		assertEquals(0.5, Example30CriticalityOrchestrator.risk(node(TYPES.EDGE_DEVICE, 1, true, 100, false)), 1e-12);
		assertEquals(0.75, Example30CriticalityOrchestrator.risk(node(TYPES.EDGE_DEVICE, 1, true, 0, false)), 1e-12);
		assertEquals(0.75, Example30CriticalityOrchestrator.risk(node(TYPES.EDGE_DEVICE, 1, false, 0, true)), 1e-12);
		assertEquals(1, Example30CriticalityOrchestrator.risk(node(TYPES.EDGE_DEVICE, 1, true, 50, true)), 1e-12);
	}

	@Test
	void theConstructorRejectsAnUnknownAlgorithm() {
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> orchestrator("ANT_COLONY", Arrays.asList(node(TYPES.CLOUD, 1, false, 0, false))));
		assertTrue(exception.getMessage().contains("CRITICALITY"));
	}

	/**
	 * A risky gateway is twice as fast as the edge data center. The root of the
	 * graph, which every other subtask waits for, goes to the data center under
	 * every criticality-aware rule and to the gateway under the latency-greedy one.
	 */
	@Test
	void theRootOfAGraphAvoidsARiskyFastDevice() {
		ComputingNode edge = node(TYPES.EDGE_DATACENTER, 10_000, false, 0, false);
		ComputingNode gateway = node(TYPES.EDGE_DEVICE, 20_000, true, 40, false);
		List<ComputingNode> nodes = Arrays.asList(edge, gateway);
		SimulationParameters.applicationList = null;

		assertEquals(1, place("LATENCY_GREEDY", nodes, edge, "capture"));
		assertEquals(0, place("RELIABLE_INNER", nodes, edge, "capture"));
		assertEquals(0, place("DOWNSTREAM_REACH", nodes, edge, "capture"));
		assertEquals(0, place("CRITICALITY", nodes, edge, "detect"));
		assertEquals(0, place("CRITICALITY_C", nodes, edge, "detect"));
	}

	/**
	 * The composite centrality peaks in the middle of a pipeline, where betweenness
	 * and closeness are highest, while the root is the subtask the most others wait
	 * for. The index and the reach rule therefore protect different subtasks.
	 */
	@Test
	void theIndexRanksTheMiddleOfAPipelineAboveItsRoot() {
		Example30CriticalityIndex index = new Example30CriticalityIndex(1, 0, 0);
		index.addApplication(0, "Soft Real-time", video());
		assertTrue(index.score(0, "detect") > index.score(0, "capture"));
		assertTrue(index.reach(0, "capture") > index.reach(0, "detect"));

		ComputingNode edge = node(TYPES.EDGE_DATACENTER, 10_000, false, 0, false);
		ComputingNode gateway = node(TYPES.EDGE_DEVICE, 20_000, true, 40, false);
		assertEquals(1, place("CRITICALITY", Arrays.asList(edge, gateway), edge, "capture"));
	}

	/** The last subtask has no dependents: the fast device wins under every rule but governance. */
	@Test
	void aLeafStillUsesTheFastDevice() {
		ComputingNode edge = node(TYPES.EDGE_DATACENTER, 10_000, false, 0, false);
		ComputingNode gateway = node(TYPES.EDGE_DEVICE, 20_000, true, 40, false);
		List<ComputingNode> nodes = Arrays.asList(edge, gateway);

		assertEquals(1, place("LATENCY_GREEDY", nodes, edge, "notify"));
		assertEquals(1, place("RELIABLE_INNER", nodes, edge, "notify"));
		assertEquals(1, place("DOWNSTREAM_REACH", nodes, edge, "notify"));
	}

	private int place(String algorithm, List<ComputingNode> nodes, ComputingNode edge, String subtask) {
		Example30CriticalityOrchestrator orchestrator = orchestrator(algorithm, nodes);
		Example30CriticalityIndex index = registerVideo(orchestrator);
		Task task = task(1, 0, subtask, 5_000, 1.0, edge);
		assertTrue(index != null);
		return orchestrator.findComputingNode(ALL, task);
	}

	private Example30CriticalityIndex registerVideo(Example30CriticalityOrchestrator orchestrator) {
		Example30CriticalityIndex index = orchestrator.index();
		index.addApplication(0, "Soft Real-time", video());
		return index;
	}

	@Test
	void theDelayEstimateForgetsAFailedTask() {
		ComputingNode edge = node(TYPES.EDGE_DATACENTER, 10_000, false, 0, false);
		Example30CriticalityOrchestrator orchestrator = orchestrator("LATENCY_GREEDY", Arrays.asList(edge));
		registerVideo(orchestrator);
		Task first = task(1, 0, "capture", 10_000, 10, edge);
		Task second = task(2, 0, "capture", 10_000, 10, edge);
		orchestrator.findComputingNode(ALL, first);
		assertEquals(1, orchestrator.pending(0));
		orchestrator.taskEnded(first);
		assertEquals(0, orchestrator.pending(0));
		orchestrator.findComputingNode(ALL, second);
		when(second.getStatus()).thenReturn(Task.Status.FAILED);
		orchestrator.resultsReturned(second);
		assertEquals(0, orchestrator.pending(0));
	}

	@Test
	@Tag("integration")
	void aShortCrashRunWritesOneLineOfMetrics() throws Exception {
		Path out = Files.createTempFile("example30-", ".jsonl");
		Path output = Files.createTempDirectory("example30-output");
		System.setProperty("example30.scenario", "crash");
		System.setProperty("example30.devices", "20");
		System.setProperty("example30.seed", "3");
		System.setProperty("example30.algorithms", "CRITICALITY");
		System.setProperty("example30.out", out.toString());
		System.setProperty("example30.output", output.toString() + "/");
		SimLogCapture.mute();
		try {
			new Example30();
		} finally {
			SimLogCapture.unmute();
			for (String key : Arrays.asList("example30.scenario", "example30.devices", "example30.seed",
					"example30.algorithms", "example30.out", "example30.output"))
				System.clearProperty(key);
		}
		List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);
		assertEquals(1, lines.size());
		String json = lines.get(0);
		assertTrue(json.contains("\"algorithm\":\"CRITICALITY\""), json);
		assertTrue(json.contains("\"devices\":20"), json);
		assertTrue(json.contains("\"requests_generated\":"), json);
	}

	/** Composite centrality from _shared/cei_ref.py with networkx 3.4.2. */
	static final class ExpectedCentrality {
		static final Map<String, Double> VIDEO = new LinkedHashMap<>();
		static {
			VIDEO.put("capture", 0.544317192194322);
			VIDEO.put("preprocess", 0.8014069241812191);
			VIDEO.put("detect", 1.0);
			VIDEO.put("classify", 0.5236119561917537);
			VIDEO.put("track", 0.5236119561917537);
			VIDEO.put("aggregate", 0.6729084831885149);
			VIDEO.put("notify", 0.16548888881983356);
		}
		static final Map<String, Double> HEALTH = new LinkedHashMap<>();
		static {
			HEALTH.put("ingest", 1.0);
			HEALTH.put("ecg", 0.49146585266654497);
			HEALTH.put("spo2", 0.49146585266654497);
			HEALTH.put("motion", 0.49146585266654497);
			HEALTH.put("fuse", 0.8579694476412622);
			HEALTH.put("alert", 0.19403129628065238);
		}
	}
}
