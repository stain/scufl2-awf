package uk.org.taverna.scufl2.awf;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import uk.org.taverna.scufl2.api.activity.Activity;
import uk.org.taverna.scufl2.api.common.Scufl2Tools;
import uk.org.taverna.scufl2.api.configurations.Configuration;
import uk.org.taverna.scufl2.api.container.WorkflowBundle;
import uk.org.taverna.scufl2.api.core.BlockingControlLink;
import uk.org.taverna.scufl2.api.core.DataLink;
import uk.org.taverna.scufl2.api.core.Processor;
import uk.org.taverna.scufl2.api.core.Workflow;
import uk.org.taverna.scufl2.api.io.ReaderException;
import uk.org.taverna.scufl2.api.io.WorkflowBundleIO;
import uk.org.taverna.scufl2.api.io.WorkflowBundleReader;
import uk.org.taverna.scufl2.api.port.InputProcessorPort;
import uk.org.taverna.scufl2.api.port.InputWorkflowPort;
import uk.org.taverna.scufl2.api.port.OutputProcessorPort;
import uk.org.taverna.scufl2.api.port.OutputWorkflowPort;
import uk.org.taverna.scufl2.api.port.SenderPort;

public class AwfReader implements WorkflowBundleReader {

    private static final URI TOOL = URI.create("http://ns.taverna.org.uk/2010/activity/tool");
    private static final URI BEANSHELL = URI.create("http://ns.taverna.org.uk/2010/activity/beanshell");
    
    private static final String TASK = "task_";
    private static Scufl2Tools scufl2Tools = new Scufl2Tools();
    private static WorkflowBundleIO bundleIO = new WorkflowBundleIO();
    private static final String AWF_JSON = "text/vnd.mgrast.awe.awf+json";
    private static final Charset LATIN1 = Charset.forName("latin1");
    private static ObjectMapper om = new ObjectMapper();
    
    public Set<String> getMediaTypes() {
        return Collections.singleton(AWF_JSON);
    }

    public WorkflowBundle readBundle(File bundleFile, String mediaType)
            throws ReaderException, IOException {        
        JsonNode json = om.readTree(bundleFile);
        return readJson(json);
    }

    public WorkflowBundle readBundle(InputStream inputStream, String mediaType)
            throws ReaderException, IOException {
        JsonNode json = om.readTree(inputStream);
        return readJson(json);
    }

    public WorkflowBundle readJson(JsonNode json) {
        WorkflowBundle bundle = bundleIO.createBundle();
        parseWorkflowInfo(json.get("workflow_info"), bundle);
        parseRawInputs(json.get("raw_inputs"), bundle.getMainWorkflow());
        parseVariables(json.get("variables"), bundle);
        parseTasks(json.get("tasks"), bundle);
        return bundle;
    }

    private void parseTasks(JsonNode jsonNode, WorkflowBundle bundle) {
        if (jsonNode == null) {
            return;
        }
        Workflow workflow = bundle.getMainWorkflow();
        List<OutputProcessorPort> outputs = new ArrayList<>();
        
        Set<Processor> constants = scufl2Tools.getConstants(workflow, bundle.getMainProfile());
        
        for (JsonNode task : jsonNode) {
            String name = TASK + task.get("taskid").asText();
            
            Processor proc = createProcessorIfNotExist(workflow, name);
            // TODO: parse input ports
            JsonNode inputs = task.get("inputs");
            
            Set<Processor> connectedFrom = new LinkedHashSet();
            
            for (String inputPort : iterate(inputs.fieldNames())) {
                InputProcessorPort in = new InputProcessorPort(proc, inputPort);
                in.setDepth(0);
                int source = inputs.get(inputPort).asInt();
                SenderPort senderPort;
                if (source == 0) {
                    senderPort = workflow.getInputPorts().getByName(inputPort);
                } else {
                    Processor sourceProc = createProcessorIfNotExist(workflow, TASK + source);
                    connectedFrom.add(sourceProc);
                    senderPort = createOutputPortIfNotExists(sourceProc, inputPort);
                }
                if (senderPort != null) { 
                    DataLink dataLink = new DataLink(workflow, senderPort, in);
                }
            }

            for (JsonNode portNode : task.get("outputs")) {
                OutputProcessorPort out = new OutputProcessorPort(proc, portNode.asText());
                out.setDepth(0);
                outputs.add(out);
                
            }
            for (JsonNode dependsOn : task.get("dependsOn")) {
                if (dependsOn.asText().equals("0")) {
                    // Not sure what is the point of this dependsOn :)
                    continue;
                }
                Processor waitFor = createProcessorIfNotExist(workflow, TASK + dependsOn.asText());
                if (! connectedFrom.contains(waitFor)) {
                    // Not connected, but still we depend on it - add control link
                    new BlockingControlLink(proc, waitFor);
                }
                
            }
            // TODO: "splits": 8  ??
 

            JsonNode cmd = task.path("cmd");
            String args = cmd.path("args").asText();
            for (Processor constant : constants) {
                if (args.contains("$" + constant.getName())) {
                    InputProcessorPort constPort = new InputProcessorPort(proc, constant.getName());
                    constPort.setDepth(0);
                    new DataLink(workflow, constant.getOutputPorts().getByName("value"), constPort);
                }
            }
            
            
            
            Activity act = scufl2Tools.createActivityFromProcessor(proc, bundle.getMainProfile());
            act.setType(TOOL);
            Configuration config = scufl2Tools.createConfigurationFor(act, TOOL.resolve("#Config"));           
            
            
            
            String command = cmd.path("name").asText() + " " + args;
            // TODO: Support @parameters etc. in "args"
            config.getJsonAsObjectNode().with("toolDescription").put("command", command);
            // TODO: proper Tool service instead of beanshell 
            act.setType(BEANSHELL);
            config.setType(BEANSHELL.resolve("#Config"));
            config.getJsonAsObjectNode().put("script", command);
            
        }
        
        
        // Connect up all processor outputs that are not connected to anything
        for (OutputProcessorPort out : outputs) {
            if (scufl2Tools.datalinksFrom(out).isEmpty()) {
                OutputWorkflowPort wfOut = new OutputWorkflowPort();
                wfOut.setName(out.getName());                
                workflow.getOutputPorts().addWithUniqueName(wfOut);
                new DataLink(workflow, out, wfOut);
            }
        }
        
    }

    private OutputProcessorPort createOutputPortIfNotExists(Processor proc,
            String name) {
        OutputProcessorPort port = proc.getOutputPorts().getByName(name);
        if (port == null) {
            port = new OutputProcessorPort(proc, name);
            port.setDepth(0);
        }
        return port;        
    }

    
    private Processor createProcessorIfNotExist(Workflow workflow, String name) {
        Processor proc = workflow.getProcessors().getByName(name);
        if (proc == null) {
            proc = new Processor(workflow, name);
        }
        return proc;
    }

    private void parseVariables(JsonNode jsonNode, WorkflowBundle bundle) {
        if (jsonNode == null) {
            return;
        }
        for (String variable : iterate(jsonNode.fieldNames())) {
            JsonNode variableValue = jsonNode.get(variable);
            
            Processor constant = scufl2Tools.createConstant(bundle.getMainWorkflow(), bundle.getMainProfile(), variable);
            scufl2Tools.setConstantStringValue(constant, variableValue.asText(), bundle.getMainProfile());            
        }
    }

    private void parseRawInputs(JsonNode jsonNode, Workflow workflow) {
        if(jsonNode == null) {
            return;
        }
        for (String input : iterate(jsonNode.fieldNames())) {
            InputWorkflowPort inputPort = new InputWorkflowPort(workflow, input);
            inputPort.setParent(workflow);
            inputPort.setDepth(0);
        }
    }

    private <T> Iterable<T> iterate(final Iterator<T> iterator) {
        return new Iterable<T>() {
            public Iterator<T> iterator() {
                return iterator;
            }
        };
    }

    private void parseWorkflowInfo(JsonNode jsonNode, WorkflowBundle bundle) {
        if (jsonNode == null) { 
            return;
        }
        JsonNode name = jsonNode.get("name");
        if (name != null) {
            bundle.setName(name.asText());
            bundle.getMainWorkflow().setName(name.asText());
        }
        // TODO: Also copy author/description annotations
    }

    public String guessMediaTypeForSignature(byte[] firstBytes) {
        String s = new String(firstBytes, LATIN1).trim();
        if (s.startsWith("{") && s.contains("\"workflow_info\"")) {
            return AWF_JSON;
        }
        return null;
    }

}
