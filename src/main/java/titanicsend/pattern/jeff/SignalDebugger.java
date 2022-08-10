package titanicsend.pattern.jeff;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.blend.MultiplyBlend;
import heronarts.lx.color.LXColor;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.transform.LXVector;
import titanicsend.model.TEEdgeModel;
import titanicsend.model.TEWholeModel;
import titanicsend.pattern.TEPattern;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.*;
import java.util.stream.Collectors;


/**
 *  Visualize signal paths
 */

@LXCategory("Test")
public class SignalDebugger extends TEPattern {

    public final DiscreteParameter edgeChainSelect =
            new DiscreteParameter("EdgeC", 0, 1)
                    .setDescription("Edge chain to highlight");

    public final DiscreteParameter vertexSelect =
            new DiscreteParameter("Vertices", 0, 1)
                    .setDescription("Vertex to highlight");

    public final BooleanParameter showLowPri =
            new BooleanParameter("Low?", false)
                    .setDescription("Display low priority (year 2) edges and panels");



    // Collection of edges that should be on based on parameters
    int  litEdges;


    // Add priority to edge
    private class ChainedEdge {
        TEEdgeModel edge;
        public enum Priority { HIGH, MEDIUM, LOW }
        public Enum priority;
        public int controllerVertex;
        public String signalFrom;
        public ChainedEdge fromEdge;

        ChainedEdge(String edgeId, String signalFrom, String controllerVertex, String pri) {
            this.edge = model.edgesById.get(edgeId);
            this.signalFrom = signalFrom;
            if (!controllerVertex.equals("")) {
                this.controllerVertex = Integer.parseInt(controllerVertex);
            }
            this.priority = Priority.LOW;
            if (pri.toLowerCase().contains("medium")) this.priority = Priority.MEDIUM;
            if (pri.toLowerCase().contains("high")) this.priority = Priority.HIGH;
        }
    }


    private List<ChainedEdge> chainedEdges = new ArrayList<>();
    private Map<Integer, List<ChainedEdge>> edgesByControllerVertex = new HashMap<>();

    public SignalDebugger(LX lx) {
        super(lx);
        addParameter("edgeChainSelect", edgeChainSelect);
        addParameter("vertexSelect", vertexSelect);
        addParameter("showLowPri", showLowPri);


        loadChains();
    }



    public void run(double deltaMs) {
        clearEdges();

        for (TEEdgeModel edge : model.edgesById.values()) {
            for (TEEdgeModel.Point point : edge.points) {
                colors[point.index] = 0;
            }
        }
    }



    protected void loadChains() {
        Scanner s;
        try {
            File f = new File("resources/vehicle/edge_signal_paths.tsv");
            s = new Scanner(f);
        } catch (FileNotFoundException e) {
            throw new Error("edge_signal_paths.tsv not found");
        }

        edgesByControllerVertex = new HashMap<>();
        String headerLine = s.nextLine();
        assert headerLine.endsWith("Pixels");

        while (s.hasNextLine()) {
            String line = s.nextLine();
            String[] tokens = line.split("\\t");
            assert tokens.length == 5;
            ChainedEdge chainedEdge = new ChainedEdge(tokens[0], tokens[1], tokens[2], tokens[3] );
            chainedEdges.add(chainedEdge);
            edgesByControllerVertex.putIfAbsent(chainedEdge.controllerVertex, new ArrayList<>());
        }

        for (ChainedEdge chainedEdge : chainedEdges) {
            edgesByControllerVertex.get(chainedEdge.controllerVertex).add(chainedEdge);
        }

        for (Map.Entry<Integer, List<ChainedEdge>> vertexEdges : edgesByControllerVertex.entrySet()) {
            List edges = vertexEdges.getValue();
            ChainedEdge first = edges.stream()
                    .filter((e) -> e.signalFrom.equals("Controller"))
                    .findFirst();
            edges.remove(first);
            edges.add(0, first);
            ChainedEdge first = edges.stream()
                    .filter((e) -> e.signalFrom.equals("Controller"))
                    .findFirst();

        }

    }

    @Override
    public void onParameterChanged(LXParameter parameter) {
        super.onParameterChanged(parameter);

    }
}
