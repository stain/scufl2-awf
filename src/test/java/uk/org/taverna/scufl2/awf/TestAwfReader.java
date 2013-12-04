package uk.org.taverna.scufl2.awf;

import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import uk.org.taverna.scufl2.api.common.Scufl2Tools;
import uk.org.taverna.scufl2.api.container.WorkflowBundle;
import uk.org.taverna.scufl2.api.core.BlockingControlLink;
import uk.org.taverna.scufl2.api.core.DataLink;
import uk.org.taverna.scufl2.api.core.Processor;
import uk.org.taverna.scufl2.api.io.WorkflowBundleIO;
import uk.org.taverna.scufl2.api.port.InputProcessorPort;
import uk.org.taverna.scufl2.api.port.InputWorkflowPort;
import uk.org.taverna.scufl2.api.port.OutputProcessorPort;
import uk.org.taverna.scufl2.api.port.ReceiverPort;

public class TestAwfReader {
    private static WorkflowBundleIO bundleIO = new WorkflowBundleIO();
    private static Scufl2Tools scufl2Tools = new Scufl2Tools();

    
    @Test
    public void readMgrastShort() throws Exception {       
        WorkflowBundle bundle = bundleIO.readBundle(getClass().getResource("/mgrast-short.awf"), null);
        
        InputWorkflowPort wfIn = bundle.getMainWorkflow().getInputPorts().getByName("#i_1");
        assertNotNull(wfIn);
        
        Set<Processor> constants = scufl2Tools.getConstants(bundle.getMainWorkflow(), bundle.getMainProfile());
        assertEquals(5, constants.size());
        Processor fgs_type = bundle.getMainWorkflow().getProcessors().getByName("fgs_type");
        assertEquals("454", scufl2Tools.getConstantStringValue(fgs_type, bundle.getMainProfile()));
        
        Set<String> processors = bundle.getMainWorkflow().getProcessors().getNames();  
        System.out.println(processors);
        assertEquals(6+constants.size(), processors.size());
        assertTrue(processors.contains("task_1"));
        assertTrue(processors.contains("task_2")); // ..
        assertTrue(processors.contains("task_6"));
        Processor task1 = bundle.getMainWorkflow().getProcessors().getByName("task_1");
        InputProcessorPort procIn = task1.getInputPorts().first();
        assertEquals("#i_1", procIn.getName());
        assertEquals(wfIn, scufl2Tools.datalinksTo(procIn).get(0).getReceivesFrom());
        
        OutputProcessorPort out = task1.getOutputPorts().getByName("prep.passed.fna");        
        List<DataLink> linksFrom = scufl2Tools.datalinksFrom(out);
        assertEquals(1, linksFrom.size());
        ReceiverPort linksTo = linksFrom.get(0).getSendsTo();
        assertEquals("prep.passed.fna", linksTo.getName());
        
        Processor task2 = ((InputProcessorPort) linksTo).getParent();
        assertEquals("task_2", task2.getName());
        
        List<BlockingControlLink> blocking = scufl2Tools.controlLinksBlocking(task2);
        assertEquals(1, blocking.size());
        assertEquals(task1, blocking.get(0).getUntilFinished());
        
        // TODO: Check cmd 
        
    }
    
    @Test
    public void pipelineExampleAsWfBundle() throws Exception {       
        WorkflowBundle bundle = bundleIO.readBundle(getClass().getResource("/pipeline_example.awf"), null);
        Path dest = Files.createTempFile("pipeline_example", ".wfbundle");
        bundleIO.writeBundle(bundle, dest.toFile(), "application/vnd.taverna.scufl2.workflow-bundle");
        System.out.println("Written to " + dest);
    }
    

    @Test
    public void workflowExampleAsWfBundle() throws Exception {       
        WorkflowBundle bundle = bundleIO.readBundle(getClass().getResource("/workflow_example.awf"), null);
        Path dest = Files.createTempFile("workflow_example", ".wfbundle");
        bundleIO.writeBundle(bundle, dest.toFile(), "application/vnd.taverna.scufl2.workflow-bundle");
        System.out.println("Written to " + dest);
    }
    
    
    @Test
    public void workflowExampleAsWfdesc() throws Exception {       
        WorkflowBundle bundle = bundleIO.readBundle(getClass().getResource("/workflow_example.awf"), null);
        Path dest = Files.createTempFile("workflow_example", ".wfdesc.ttl");
        bundleIO.writeBundle(bundle, dest.toFile(), "text/vnd.wf4ever.wfdesc+turtle");
        System.out.println("Written to " + dest);
    }
    
}
